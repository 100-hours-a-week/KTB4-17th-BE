package com.team.dating_backend.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.notification.cursor.NotificationCursorCodec;
import com.team.dating_backend.notification.dto.result.NotificationCreationResult;
import com.team.dating_backend.notification.dto.read.NotificationCursor;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.dto.response.NotificationListResponse;
import com.team.dating_backend.notification.entity.Notification;
import com.team.dating_backend.notification.enums.NotificationCategory;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.service.NotificationAccessService;
import com.team.dating_backend.notification.service.NotificationDeleteService;
import com.team.dating_backend.notification.service.NotificationListService;
import com.team.dating_backend.notification.service.NotificationReadService;
import com.team.dating_backend.notification.service.ChatNotificationReadService;
import com.team.dating_backend.notification.service.NotificationUnreadCountService;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(
    properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
    }
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    {NotificationAccessService.class, NotificationDeleteService.class, NotificationListService.class,
        NotificationUnreadCountService.class, NotificationReadService.class,
        ChatNotificationReadService.class, NotificationCursorCodec.class}
)
@Testcontainers
class NotificationRepositoryIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 12, 0);

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private NotificationRepository repository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private NotificationDeleteService deleteService;

    @Autowired
    private NotificationListService listService;

    @Autowired
    private NotificationCursorCodec cursorCodec;

    @Autowired
    private NotificationUnreadCountService unreadCountService;

    @Autowired
    private NotificationReadService readService;

    @Autowired
    private ChatNotificationReadService chatReadService;

    private TransactionTemplate transaction;
    private Long userId;
    private Long otherUserId;

    @BeforeEach
    void setUp() {
        otherUserId = null;
        transaction = new TransactionTemplate(transactionManager);
        userId = transaction.execute(status -> {
            User user = User.create("테스트", LocalDate.of(2000, 1, 1), Gender.FEMALE, NOW);
            user.activate(NOW);
            return userRepository.saveAndFlush(user).getId();
        });
    }

    @AfterEach
    void cleanUp() {
        transaction.executeWithoutResult(status -> {
            if (otherUserId != null) {
                jdbc.update("DELETE FROM notifications WHERE user_id = ?", otherUserId);
                jdbc.update("DELETE FROM users WHERE id = ?", otherUserId);
            }
            jdbc.update("DELETE FROM notifications WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        });
    }

    @Test
    void 같은_회원에게_같은_유형의_알림을_여러_건_저장할_수_있다() {
        Long firstId = saveNotification();
        Long secondId = saveNotification();

        assertThat(secondId).isGreaterThan(firstId);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = ?",
            Long.class, userId, "LIKE")).isEqualTo(2L);
        assertThat(repository.findById(firstId).orElseThrow().getSourceEventId()).isNull();
        assertThat(repository.findById(secondId).orElseThrow().getSourceEventId()).isNull();
    }

    @Test
    void 같은_원본_키의_저장을_거절한_뒤_별도_조회로_읽고_삭제한_기존_알림을_찾는다() {
        String sourceEventId = "LIKE_SENT:501";
        Long notificationId = transaction.execute(status -> {
            Notification notification = sourceNotification(sourceEventId, userId, NotificationType.LIKE);
            notification.markAsRead(NOW.plusMinutes(1));
            notification.markAsDeleted(NOW.plusMinutes(2));
            return repository.saveAndFlush(notification).getId();
        });
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM notifications WHERE id = ?", notificationId);

        assertThatThrownBy(() -> saveSourceNotification(sourceEventId, userId, NotificationType.LIKE))
            .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                exception -> assertThat(exception.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class,
                    sql -> {
                        assertThat(sql.getErrorCode()).isEqualTo(1062);
                        assertThat(sql.getMessage()).contains("uk_notifications_source_recipient_type");
                    }));

        Notification existing = transaction.execute(status -> repository.findBySourceEventIdAndUserIdAndType(
            sourceEventId, userId, NotificationType.LIKE).orElseThrow());

        assertThat(existing.getId()).isEqualTo(notificationId);
        assertThat(existing.getReadAt()).isEqualTo(NOW.plusMinutes(1));
        assertThat(existing.getDeletedAt()).isEqualTo(NOW.plusMinutes(2));
        assertThat(jdbc.queryForMap("SELECT * FROM notifications WHERE id = ?", notificationId)).isEqualTo(before);
        assertThat(repository.countVisibleUnreadByUserId(userId)).isZero();
    }

    @Test
    void 원본_식별자와_수신자와_유형을_모두_구분하여_저장하고_조회한다() {
        otherUserId = saveOtherUser();
        Long original = saveSourceNotification("event-1", userId, NotificationType.LIKE);
        Long differentSource = saveSourceNotification("event-2", userId, NotificationType.LIKE);
        Long differentUser = saveSourceNotification("event-1", otherUserId, NotificationType.LIKE);
        Long differentType = saveSourceNotification("event-1", userId, NotificationType.MATCH);

        assertThat(repository.findBySourceEventIdAndUserIdAndType("event-1", userId, NotificationType.LIKE)
            .orElseThrow().getId()).isEqualTo(original);
        assertThat(repository.findBySourceEventIdAndUserIdAndType("event-2", userId, NotificationType.LIKE)
            .orElseThrow().getId()).isEqualTo(differentSource);
        assertThat(repository.findBySourceEventIdAndUserIdAndType("event-1", otherUserId, NotificationType.LIKE)
            .orElseThrow().getId()).isEqualTo(differentUser);
        assertThat(repository.findBySourceEventIdAndUserIdAndType("event-1", userId, NotificationType.MATCH)
            .orElseThrow().getId()).isEqualTo(differentType);
        assertThat(repository.findBySourceEventIdAndUserIdAndType("event-2", userId, NotificationType.MATCH)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"like_sent:501", "LIKE_SENT:501 "})
    void 원본_식별자의_대소문자와_후행_공백을_다른_키로_저장하고_정확히_조회한다(String variant) {
        Long original = saveSourceNotification("LIKE_SENT:501", userId, NotificationType.LIKE);
        Long different = saveSourceNotification(variant, userId, NotificationType.LIKE);

        assertThat(repository.findBySourceEventIdAndUserIdAndType("LIKE_SENT:501", userId, NotificationType.LIKE)
            .orElseThrow().getId()).isEqualTo(original);
        assertThat(repository.findBySourceEventIdAndUserIdAndType(variant, userId, NotificationType.LIKE)
            .orElseThrow().getId()).isEqualTo(different);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "😀"})
    void 유니코드_255자_원본_식별자를_변형_없이_저장하고_조회한다(String character) {
        String sourceEventId = character.repeat(255);
        Long id = saveSourceNotification(sourceEventId, userId, NotificationType.LIKE);

        Notification stored = repository.findBySourceEventIdAndUserIdAndType(
            sourceEventId, userId, NotificationType.LIKE).orElseThrow();

        assertThat(stored.getId()).isEqualTo(id);
        assertThat(stored.getSourceEventId()).isEqualTo(sourceEventId);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 같은_원본_키의_동시_INSERT는_선행_commit이면_중복_거절하고_rollback이면_저장한다(boolean rollbackFirst)
        throws Exception {
        String sourceEventId = "LIKE_SENT:501";
        AtomicReference<Long> firstId = new AtomicReference<>();
        AtomicReference<Long> secondId = new AtomicReference<>();

        runWhileFirstTransactionIsOpen(
            () -> firstId
                .set(repository.saveAndFlush(sourceNotification(sourceEventId, userId, NotificationType.LIKE)).getId()),
            () -> {
                if (rollbackFirst) {
                    secondId.set(saveSourceNotification(sourceEventId, userId, NotificationType.LIKE));
                } else {
                    assertThatThrownBy(() -> saveSourceNotification(sourceEventId, userId, NotificationType.LIKE))
                        .isInstanceOf(DataIntegrityViolationException.class);
                }
            }, rollbackFirst);

        Notification stored = repository.findBySourceEventIdAndUserIdAndType(
            sourceEventId, userId, NotificationType.LIKE).orElseThrow();
        assertThat(stored.getId()).isEqualTo(rollbackFirst ? secondId.get() : firstId.get());
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE source_event_id = ? AND user_id = ? AND type = 'LIKE'",
            Long.class, sourceEventId, userId)).isEqualTo(1L);
    }

    @Test
    void 채팅방_일괄_읽음은_해당_회원과_방의_미확인_채팅_알림만_갱신한다() {
        otherUserId = saveOtherUser();
        List<Long> ids = transaction.execute(status -> {
            User owner = userRepository.getReferenceById(userId);
            User other = userRepository.getReferenceById(otherUserId);
            List<Notification> notifications = List.of(
                new Notification(owner, NotificationType.CHAT_MESSAGE, "제목", "본문", "CHAT_ROOM", 10L, NOW),
                new Notification(owner, NotificationType.CHAT_MESSAGE, "제목", "본문", "CHAT_ROOM", 10L, NOW),
                new Notification(owner, NotificationType.CHAT_MESSAGE, "제목", "본문", "CHAT_ROOM", 20L, NOW),
                new Notification(owner, NotificationType.MATCH, "제목", "본문", "CHAT_ROOM", 10L, NOW),
                new Notification(owner, NotificationType.CHAT_MESSAGE, "제목", "본문", "PROFILE", 10L, NOW),
                new Notification(other, NotificationType.CHAT_MESSAGE, "제목", "본문", "CHAT_ROOM", 10L, NOW),
                new Notification(owner, NotificationType.CHAT_MESSAGE, "제목", "본문", "CHAT_ROOM", 10L, NOW),
                new Notification(owner, NotificationType.CHAT_MESSAGE, "제목", "본문", "CHAT_ROOM", 10L, NOW));
            notifications.get(6).markAsRead(NOW.minusMinutes(1));
            notifications.get(7).markAsDeleted(NOW);
            return repository.saveAllAndFlush(notifications).stream().map(Notification::getId).toList();
        });

        int updated = transaction.execute(status -> repository.markVisibleUnreadChatNotificationsAsRead(
            userId, 10L, NOW.plusMinutes(1)));
        int repeated = transaction.execute(status -> repository.markVisibleUnreadChatNotificationsAsRead(
            userId, 10L, NOW.plusMinutes(2)));

        assertThat(updated).isEqualTo(2);
        assertThat(repeated).isZero();
        for (int index = 0; index < ids.size(); index++) {
            Notification notification = repository.findById(ids.get(index)).orElseThrow();
            if (index < 2) {
                assertThat(notification.getReadAt()).isEqualTo(NOW.plusMinutes(1));
            } else if (index == 6) {
                assertThat(notification.getReadAt()).isEqualTo(NOW.minusMinutes(1));
            } else {
                assertThat(notification.getReadAt()).isNull();
            }
        }
    }

    @Test
    void 채팅_알림_읽음은_기존_트랜잭션_없이_실행할_수_없다() {
        assertThatThrownBy(() -> chatReadService.markAsRead(userId, 10L))
            .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void 잠금_조회는_읽음과_삭제_여부를_필터링하지_않는다() {
        Long notificationId = saveNotification();
        transaction.executeWithoutResult(status -> {
            Notification notification = repository.findByIdForUpdate(notificationId).orElseThrow();
            notification.markAsRead(NOW.plusMinutes(1));
            notification.markAsDeleted(NOW.plusMinutes(2));
        });

        transaction.executeWithoutResult(status -> {
            Notification stored = repository.findByIdForUpdate(notificationId).orElseThrow();
            assertThat(stored.getUserId()).isEqualTo(userId);
            assertThat(stored.getReadAt()).isEqualTo(NOW.plusMinutes(1));
            assertThat(stored.getDeletedAt()).isEqualTo(NOW.plusMinutes(2));
        });
    }

    @Test
    void 잠금_조회_중에는_다른_DB_연결이_같은_행을_잠글_수_없다() {
        Long notificationId = saveNotification();

        transaction.executeWithoutResult(status -> {
            repository.findByIdForUpdate(notificationId).orElseThrow();

            assertThatThrownBy(() -> {
                try (Connection connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id FROM notifications WHERE id = ? FOR UPDATE NOWAIT")) {
                        statement.setLong(1, notificationId);
                        try (var result = statement.executeQuery()) {
                            assertThat(result.next()).isTrue();
                        }
                    } finally {
                        connection.rollback();
                    }
                }
            }).isInstanceOfSatisfying(SQLException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(3572));
        });

        transaction
            .executeWithoutResult(status -> assertThat(repository.findByIdForUpdate(notificationId)).isPresent());
    }

    @ParameterizedTest
    @CsvSource(value = {"PROFILE,NULL", "NULL,1"}, nullValues = "NULL")
    void 직접_SQL도_관련_대상의_한쪽_필드만_저장할_수_없다(
        String targetType, Long targetId) {
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO notifications
                (user_id, type, title, body, target_type, target_id, created_at)
            VALUES (?, 'LIKE', '관심', '관심 도착', ?, ?, ?)
            """, userId, targetType, targetId, NOW))
            .isInstanceOf(DataAccessException.class)
            .rootCause().isInstanceOfSatisfying(SQLException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(3819));
    }

    @Test
    void 직접_SQL도_존재하지_않는_수신_회원을_저장할_수_없다() {
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO notifications (user_id, type, title, body, created_at)
            VALUES (?, 'LIKE', '관심', '관심 도착', ?)
            """, Long.MAX_VALUE, NOW)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 마이그레이션의_기본_인덱스는_회원_삭제_여부_ID_순서다() {
        List<String> columns = jdbc.queryForList("""
            SELECT COLUMN_NAME
            FROM information_schema.statistics
            WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'notifications'
                AND INDEX_NAME = 'idx_notifications_user_deleted_id'
            ORDER BY SEQ_IN_INDEX
            """, String.class);

        assertThat(columns).containsExactly("user_id", "deleted_at", "id");
    }

    @ParameterizedTest
    @EnumSource(NotificationType.class)
    void 모든_업무_유형은_DB를_왕복하고_동일한_코드로_변환된다(NotificationType type) {
        transaction.executeWithoutResult(status -> {
            Notification notification = repository.saveAndFlush(new Notification(
                userRepository.getReferenceById(userId), type, "제목", "본문", null, null, NOW));
            Long notificationId = notification.getId();
            entityManager.clear();

            Notification stored = repository.findById(notificationId).orElseThrow();
            NotificationEventResponse response = NotificationEventResponse.from(stored);

            assertThat(stored.getType()).isEqualTo(type);
            assertThat(response.notificationId()).isEqualTo(notificationId);
            assertThat(response.type()).isEqualTo(type.name());
            assertThat(response.targetType()).isNull();
            assertThat(response.targetId()).isNull();
            assertThat(response.readAt()).isNull();
        });
    }

    @Test
    void 최대_길이의_보조_유니코드_문자와_상태_시각을_손실_없이_저장한다() {
        LocalDateTime createdAt = NOW.withNano(123456789);
        transaction.executeWithoutResult(status -> {
            Notification notification = new Notification(
                userRepository.getReferenceById(userId), NotificationType.NOTICE,
                "😀".repeat(100), "😀".repeat(500), "😀".repeat(50), 1L, createdAt);
            notification.markAsRead(createdAt.plusMinutes(1));
            notification.markAsDeleted(createdAt.plusMinutes(2));
            repository.saveAndFlush(notification);
            Long notificationId = notification.getId();
            entityManager.clear();

            Notification stored = repository.findById(notificationId).orElseThrow();
            NotificationEventResponse response = NotificationEventResponse.from(stored);

            assertThat(stored.getTitle()).isEqualTo("😀".repeat(100));
            assertThat(stored.getBody()).isEqualTo("😀".repeat(500));
            assertThat(stored.getTargetType()).isEqualTo("😀".repeat(50));
            assertThat(stored.getTargetId()).isEqualTo(1L);
            assertThat(stored.getCreatedAt())
                .isEqualTo(createdAt.truncatedTo(ChronoUnit.MICROS));
            assertThat(stored.getReadAt())
                .isEqualTo(createdAt.plusMinutes(1).truncatedTo(ChronoUnit.MICROS));
            assertThat(stored.getDeletedAt())
                .isEqualTo(createdAt.plusMinutes(2).truncatedTo(ChronoUnit.MICROS));
            assertThat(response.readAt()).isEqualTo(stored.getReadAt());
        });
    }

    @Test
    void 분리된_Entity에서도_회원_지연_로딩_없이_저장_결과를_구성한다() {
        transaction.executeWithoutResult(status -> {
            Notification notification = repository.saveAndFlush(Notification.fromSourceEvent(
                "CHAT_MESSAGE_SENT:7", userRepository.getReferenceById(userId), NotificationType.CHAT_MESSAGE,
                "채팅", "메시지 도착", "CHAT_ROOM", 7L, NOW));
            Long notificationId = notification.getId();
            entityManager.clear();
            Notification detached = repository.findById(notificationId).orElseThrow();
            assertThat(Hibernate.isInitialized(detached.getUser())).isFalse();
            entityManager.clear();

            NotificationCreationResult result = NotificationCreationResult.from(detached);

            assertThat(Hibernate.isInitialized(detached.getUser())).isFalse();
            assertThat(result.recipientUserId()).isEqualTo(userId);
            assertThat(result.notification()).isEqualTo(new NotificationEventResponse(
                notificationId, "CHAT_MESSAGE", "채팅", "메시지 도착", "CHAT_ROOM", 7L,
                null, NOW));
            detached.markAsRead(NOW.plusMinutes(1));
            assertThat(result.notification().readAt()).isNull();
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 삭제_Service는_읽음_상태를_유지하고_반복_요청에도_최초_삭제_시각을_보존한다(
        boolean read) {
        Long notificationId = saveNotification();
        LocalDateTime readAt = read ? NOW.plusMinutes(1) : null;
        if (read) {
            transaction.executeWithoutResult(status -> repository
                .findByIdForUpdate(notificationId).orElseThrow().markAsRead(readAt));
        }

        deleteService.deleteNotification(userId, notificationId);
        Notification first = repository.findById(notificationId).orElseThrow();
        LocalDateTime deletedAt = first.getDeletedAt();
        assertThat(deletedAt).isNotNull();
        assertThat(first.getReadAt()).isEqualTo(readAt);

        deleteService.deleteNotification(userId, notificationId);
        Notification repeated = repository.findById(notificationId).orElseThrow();

        assertThat(repeated.getDeletedAt()).isEqualTo(deletedAt);
        assertThat(repeated.getReadAt()).isEqualTo(readAt);
        assertThat(repeated.getCreatedAt()).isEqualTo(NOW);
        assertThat(repeated.getTitle()).isEqualTo("관심");
        assertThat(repeated.getBody()).isEqualTo("관심 도착");
        assertThat(repeated.getType()).isEqualTo(NotificationType.LIKE);
    }

    @Test
    void 두_삭제_Service_요청이_동시에_시작해도_같은_최초_삭제_시각을_확인한다()
        throws Exception {
        Long notificationId = saveNotification();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<LocalDateTime>> results = List.of(0, 1).stream()
                .map(index -> executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    deleteService.deleteNotification(userId, notificationId);
                    return jdbc.queryForObject(
                        "SELECT deleted_at FROM notifications WHERE id = ?",
                        LocalDateTime.class, notificationId);
                }))
                .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            LocalDateTime first = results.getFirst().get(10, TimeUnit.SECONDS);
            LocalDateTime second = results.getLast().get(10, TimeUnit.SECONDS);

            assertThat(first).isNotNull().isEqualTo(second);
            assertThat(jdbc.queryForObject("SELECT deleted_at FROM notifications WHERE id = ?",
                LocalDateTime.class, notificationId)).isEqualTo(first);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(NotificationCategory.class)
    void 카테고리별로_본인_미삭제_알림만_생성_시각_내림차순으로_조회한다(NotificationCategory category) {
        otherUserId = saveOtherUser();
        Map<NotificationType, Long> visibleIds = new EnumMap<>(NotificationType.class);
        int offset = 0;
        for (NotificationType type : NotificationType.values()) {
            visibleIds.put(type, saveNotification(userId, type, NOW.minusSeconds(offset++), false));
            saveNotification(userId, type, NOW, true);
            saveNotification(otherUserId, type, NOW, false);
        }
        List<NotificationType> expectedTypes = switch (category) {
            case ALL -> List.of(NotificationType.LIKE, NotificationType.MATCH, NotificationType.CHAT_MESSAGE,
                NotificationType.PERSONA_TRAINING_COMPLETED, NotificationType.FACE_ANALYSIS_COMPLETED,
                NotificationType.AI_PRACTICE_CHAT_COMPLETED, NotificationType.AI_SIMULATION_REPORT_COMPLETED,
                NotificationType.NOTICE);
            case INTEREST -> List.of(NotificationType.LIKE, NotificationType.MATCH);
            case CHAT -> List.of(NotificationType.CHAT_MESSAGE);
            case SYSTEM -> List.of(NotificationType.PERSONA_TRAINING_COMPLETED,
                NotificationType.FACE_ANALYSIS_COMPLETED, NotificationType.AI_PRACTICE_CHAT_COMPLETED,
                NotificationType.AI_SIMULATION_REPORT_COMPLETED, NotificationType.NOTICE);
        };

        NotificationListResponse response = listService.getNotifications(userId, category, null, 20);

        assertThat(response.items()).extracting(NotificationListResponse.Item::notificationId)
            .containsExactlyElementsOf(expectedTypes.stream().map(visibleIds::get).toList());
        assertThat(response.items()).extracting(NotificationListResponse.Item::type)
            .containsExactlyElementsOf(expectedTypes.stream().map(NotificationType::name).toList());
        assertThat(response.pageInfo()).isEqualTo(new NotificationListResponse.PageInfo(null, false));
    }

    @ParameterizedTest
    @EnumSource(NotificationCategory.class)
    void 복합_경계는_동일_시각과_지연_저장을_이어_조회하고_필터와_삭제된_경계를_유지한다(
        NotificationCategory category) {
        NotificationType type = switch (category) {
            case ALL, INTEREST -> NotificationType.LIKE;
            case CHAT -> NotificationType.CHAT_MESSAGE;
            case SYSTEM -> NotificationType.AI_PRACTICE_CHAT_COMPLETED;
        };
        LocalDateTime createdAt = NOW.plusNanos(123_456_000);
        LocalDateTime older = createdAt.minusNanos(1_000);
        Long newerWithLowerId = saveNotification(userId, type, createdAt.plusNanos(1_000), false);
        Long oldest = saveNotification(userId, type, older, false);
        Long sameTimeOlder = saveNotification(userId, type, createdAt, false);
        otherUserId = saveOtherUser();
        saveNotification(otherUserId, type, createdAt, false);
        saveNotification(otherUserId, type, older, false);
        saveNotification(userId, type, older, true);
        if (category != NotificationCategory.ALL) {
            NotificationType otherType = category == NotificationCategory.CHAT
                ? NotificationType.LIKE
                : NotificationType.CHAT_MESSAGE;
            saveNotification(userId, otherType, createdAt, false);
            saveNotification(userId, otherType, older, false);
        }
        Long deletedCursor = saveNotification(userId, type, createdAt, true);
        Long newest = saveNotification(userId, type, createdAt, false);
        Long delayed = saveNotification(userId, type, older, false);

        NotificationListResponse first = listService.getNotifications(userId, category, null, 2);
        NotificationListResponse second = listService.getNotifications(
            userId, category, cursorCodec.decode(first.pageInfo().nextCursor()), 20);
        NotificationListResponse fromDeleted = listService.getNotifications(
            userId, category, new NotificationCursor(createdAt, deletedCursor), 20);

        assertThat(delayed).isGreaterThan(newest);
        assertThat(first.items()).extracting(NotificationListResponse.Item::notificationId)
            .containsExactly(newerWithLowerId, newest);
        assertThat(first.pageInfo().hasNext()).isTrue();
        assertThat(cursorCodec.decode(first.pageInfo().nextCursor()))
            .isEqualTo(new NotificationCursor(createdAt, newest));
        assertThat(second.items()).extracting(NotificationListResponse.Item::notificationId)
            .containsExactly(sameTimeOlder, delayed, oldest);
        assertThat(second.pageInfo()).isEqualTo(new NotificationListResponse.PageInfo(null, false));
        assertThat(fromDeleted.items()).extracting(NotificationListResponse.Item::notificationId)
            .containsExactly(sameTimeOlder, delayed, oldest);
    }

    @Test
    void 최대_크기_20건과_추가_한_건의_경계를_페이지_누락_없이_처리한다() {
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            ids.add(saveNotification(userId, NotificationType.NOTICE, NOW, false));
        }

        NotificationListResponse first = listService.getNotifications(
            userId, NotificationCategory.ALL, null, 20);
        NotificationListResponse second = listService.getNotifications(
            userId, NotificationCategory.ALL, cursorCodec.decode(first.pageInfo().nextCursor()), 20);

        assertThat(first.items()).extracting(NotificationListResponse.Item::notificationId)
            .containsExactlyElementsOf(ids.subList(1, 21).reversed());
        assertThat(first.pageInfo().hasNext()).isTrue();
        assertThat(cursorCodec.decode(first.pageInfo().nextCursor()))
            .isEqualTo(new NotificationCursor(NOW, ids.get(1)));
        assertThat(second.items()).extracting(NotificationListResponse.Item::notificationId)
            .containsExactly(ids.getFirst());
        assertThat(second.pageInfo()).isEqualTo(new NotificationListResponse.PageInfo(null, false));
    }

    @Test
    void 조회는_읽은_알림을_포함하고_저장된_문구와_대상과_상태를_변경하지_않는다() {
        Long readId = saveNotification(userId, NotificationType.CHAT_MESSAGE, NOW, false);
        Long unreadId = saveNotification(userId, NotificationType.NOTICE, NOW, false);
        saveNotification(userId, NotificationType.MATCH, NOW, true);
        transaction.executeWithoutResult(status -> {
            Notification notification = repository.findById(readId).orElseThrow();
            notification.markAsRead(NOW.plusMinutes(1));
        });
        List<Map<String, Object>> before = jdbc.queryForList(
            "SELECT * FROM notifications WHERE user_id = ? ORDER BY id", userId);

        NotificationListResponse response = listService.getNotifications(
            userId, NotificationCategory.ALL, null, 20);

        assertThat(response.items()).containsExactly(
            new NotificationListResponse.Item(unreadId, "NOTICE", "저장된 제목", "저장된 본문",
                "CHAT_ROOM", 999999L, null, NOW),
            new NotificationListResponse.Item(readId, "CHAT_MESSAGE", "저장된 제목", "저장된 본문",
                "CHAT_ROOM", 999999L, NOW.plusMinutes(1), NOW));
        assertThat(jdbc.queryForList(
            "SELECT * FROM notifications WHERE user_id = ? ORDER BY id", userId)).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(NotificationCategory.class)
    void 알림이_없거나_커서_이후_대상이_없으면_빈_목록을_반환한다(NotificationCategory category) {
        NotificationListResponse empty = listService.getNotifications(userId, category, null, 20);
        saveNotification(userId, NotificationType.AI_PRACTICE_CHAT_COMPLETED, NOW, false);

        NotificationListResponse beyond = listService.getNotifications(
            userId, category, new NotificationCursor(NOW.minusNanos(1_000), Long.MAX_VALUE), 20);

        assertThat(empty).isEqualTo(new NotificationListResponse(
            List.of(), new NotificationListResponse.PageInfo(null, false)));
        assertThat(beyond).isEqualTo(empty);
    }

    @Test
    void 미읽음_집계는_모든_업무_유형을_포함하고_다른_회원과_읽음과_삭제_알림을_제외한다() {
        otherUserId = saveOtherUser();
        for (NotificationType type : NotificationType.values()) {
            saveNotification(userId, type, NOW, false);
            saveNotification(otherUserId, type, NOW, false);
        }
        Long readId = saveNotification(userId, NotificationType.LIKE, NOW, false);
        saveNotification(userId, NotificationType.CHAT_MESSAGE, NOW, true);
        Long readDeletedId = saveNotification(userId, NotificationType.NOTICE, NOW, true);
        transaction.executeWithoutResult(status -> {
            repository.findById(readId).orElseThrow().markAsRead(NOW.plusMinutes(1));
            repository.findById(readDeletedId).orElseThrow().markAsRead(NOW.plusMinutes(1));
        });
        List<Map<String, Object>> before = jdbc.queryForList(
            "SELECT * FROM notifications WHERE user_id = ? ORDER BY id", userId);

        long count = unreadCountService.getUnreadCount(userId).count();

        assertThat(count).isEqualTo(NotificationType.values().length);
        assertThat(jdbc.queryForList(
            "SELECT * FROM notifications WHERE user_id = ? ORDER BY id", userId)).isEqualTo(before);
    }

    @Test
    void 알림이_없거나_집계에서_제외되는_알림만_있으면_0을_반환한다() {
        assertThat(unreadCountService.getUnreadCount(userId).count()).isZero();
        otherUserId = saveOtherUser();
        saveNotification(otherUserId, NotificationType.LIKE, NOW, false);
        saveNotification(userId, NotificationType.CHAT_MESSAGE, NOW, true);
        Long readId = saveNotification(userId, NotificationType.NOTICE, NOW, false);
        transaction.executeWithoutResult(status -> repository.findById(readId).orElseThrow()
            .markAsRead(NOW.plusMinutes(1)));

        assertThat(unreadCountService.getUnreadCount(userId).count()).isZero();
    }

    @Test
    void 미읽음_개수에는_목록의_20건_제한을_적용하지_않는다() {
        for (int index = 0; index < 25; index++) {
            saveNotification(userId, NotificationType.AI_PRACTICE_CHAT_COMPLETED, NOW, false);
        }

        assertThat(unreadCountService.getUnreadCount(userId).count()).isEqualTo(25L);
    }

    @Test
    void 읽음과_삭제가_커밋되면_후속_미읽음_집계에서_제외한다() {
        Long readId = saveNotification(userId, NotificationType.LIKE, NOW, false);
        Long deleteId = saveNotification(userId, NotificationType.CHAT_MESSAGE, NOW, false);
        assertThat(unreadCountService.getUnreadCount(userId).count()).isEqualTo(2L);

        transaction.executeWithoutResult(status -> repository.findById(readId).orElseThrow()
            .markAsRead(NOW.plusMinutes(1)));

        assertThat(unreadCountService.getUnreadCount(userId).count()).isEqualTo(1L);

        deleteService.deleteNotification(userId, deleteId);

        assertThat(unreadCountService.getUnreadCount(userId).count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 읽음_Service는_최초_시각과_다른_컬럼을_보존하고_목록과_집계에_반영한다(boolean alreadyRead) {
        Long notificationId = saveNotification(userId, NotificationType.CHAT_MESSAGE, NOW, false);
        if (alreadyRead) {
            transaction.executeWithoutResult(status -> repository.findById(notificationId).orElseThrow()
                .markAsRead(NOW.plusMinutes(1)));
        }
        Map<String, Object> before = jdbc.queryForMap(
            "SELECT * FROM notifications WHERE id = ?", notificationId);
        assertThat(unreadCountService.getUnreadCount(userId).count()).isEqualTo(alreadyRead ? 0L : 1L);

        readService.markAsRead(userId, notificationId);

        Notification stored = repository.findById(notificationId).orElseThrow();
        LocalDateTime readAt = stored.getReadAt();
        assertThat(readAt).isNotNull();
        if (alreadyRead) {
            assertThat(readAt).isEqualTo(NOW.plusMinutes(1));
        }
        Map<String, Object> after = jdbc.queryForMap(
            "SELECT * FROM notifications WHERE id = ?", notificationId);
        before.put("read_at", after.get("read_at"));
        assertThat(after).isEqualTo(before);
        assertThat(listService.getNotifications(userId, NotificationCategory.ALL, null, 20)
            .items().getFirst().readAt()).isEqualTo(readAt);
        assertThat(unreadCountService.getUnreadCount(userId).count()).isZero();

        readService.markAsRead(userId, notificationId);

        assertThat(jdbc.queryForMap(
            "SELECT * FROM notifications WHERE id = ?", notificationId)).isEqualTo(after);
    }

    @Test
    void 읽음_변경을_flush한_트랜잭션이_롤백되면_DB와_집계에_반영하지_않는다() {
        Long notificationId = saveNotification();

        transaction.executeWithoutResult(status -> {
            readService.markAsRead(userId, notificationId);
            entityManager.flush();
            status.setRollbackOnly();
        });

        assertThat(repository.findById(notificationId).orElseThrow().getReadAt()).isNull();
        assertThat(unreadCountService.getUnreadCount(userId).count()).isEqualTo(1L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 동시_읽음은_선행_커밋의_시각을_보존하고_롤백된_시각은_재사용하지_않는다(boolean rollbackFirst)
        throws Exception {
        Long notificationId = saveNotification();
        AtomicReference<LocalDateTime> firstReadAt = new AtomicReference<>();

        runWhileFirstTransactionIsOpen(
            () -> {
                readService.markAsRead(userId, notificationId);
                firstReadAt.set(repository.findById(notificationId).orElseThrow().getReadAt());
            },
            () -> readService.markAsRead(userId, notificationId),
            rollbackFirst);

        LocalDateTime storedReadAt = repository.findById(notificationId).orElseThrow().getReadAt();
        assertThat(storedReadAt).isNotNull();
        if (rollbackFirst) {
            assertThat(storedReadAt).isNotEqualTo(firstReadAt.get());
        } else {
            assertThat(storedReadAt).isEqualTo(firstReadAt.get());
        }
        assertThat(unreadCountService.getUnreadCount(userId).count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 읽음과_삭제가_겹치면_선행_커밋에_따라_읽음_시각_보존_또는_404로_처리한다(boolean readFirst)
        throws Exception {
        Long notificationId = saveNotification();
        AtomicReference<LocalDateTime> firstChangedAt = new AtomicReference<>();

        runWhileFirstTransactionIsOpen(
            () -> {
                if (readFirst) {
                    readService.markAsRead(userId, notificationId);
                } else {
                    deleteService.deleteNotification(userId, notificationId);
                }
                Notification notification = repository.findById(notificationId).orElseThrow();
                firstChangedAt.set(readFirst ? notification.getReadAt() : notification.getDeletedAt());
            },
            () -> {
                if (readFirst) {
                    deleteService.deleteNotification(userId, notificationId);
                } else {
                    assertThatThrownBy(() -> readService.markAsRead(userId, notificationId))
                        .isInstanceOfSatisfying(NotificationBusinessException.class,
                            exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
                }
            },
            false);

        Notification stored = repository.findById(notificationId).orElseThrow();
        assertThat(stored.getDeletedAt()).isNotNull();
        if (readFirst) {
            assertThat(stored.getReadAt()).isEqualTo(firstChangedAt.get());
        } else {
            assertThat(stored.getReadAt()).isNull();
            assertThat(stored.getDeletedAt()).isEqualTo(firstChangedAt.get());
        }
        assertThat(unreadCountService.getUnreadCount(userId).count()).isZero();
    }

    private void runWhileFirstTransactionIsOpen(Runnable firstAction, Runnable secondAction,
        boolean rollbackFirst) throws Exception {
        CountDownLatch firstPrepared = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> transaction.executeWithoutResult(status -> {
                firstAction.run();
                entityManager.flush();
                firstPrepared.countDown();
                awaitLatch(releaseFirst);
                if (rollbackFirst) {
                    status.setRollbackOnly();
                }
            }));
            awaitLatch(firstPrepared);
            Future<?> second = executor.submit(() -> {
                secondStarted.countDown();
                secondAction.run();
            });
            awaitLatch(secondStarted);
            assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트 대기 중 중단", exception);
        }
    }

    private Long saveSourceNotification(String sourceEventId, Long recipientId, NotificationType type) {
        return transaction
            .execute(status -> repository.saveAndFlush(sourceNotification(sourceEventId, recipientId, type)).getId());
    }

    private Notification sourceNotification(String sourceEventId, Long recipientId, NotificationType type) {
        return Notification.fromSourceEvent(sourceEventId, userRepository.getReferenceById(recipientId),
            type, "저장된 제목", "저장된 본문", "CHAT_ROOM", 999999L, NOW);
    }

    private Long saveOtherUser() {
        return transaction.execute(status -> {
            User user = User.create("다른회원", LocalDate.of(2000, 1, 1), Gender.MALE, NOW);
            user.activate(NOW);
            return userRepository.saveAndFlush(user).getId();
        });
    }

    private Long saveNotification(Long recipientId, NotificationType type, LocalDateTime createdAt,
        boolean deleted) {
        return transaction.execute(status -> {
            Notification notification = new Notification(userRepository.getReferenceById(recipientId),
                type, "저장된 제목", "저장된 본문", "CHAT_ROOM", 999999L, createdAt);
            if (deleted) {
                notification.markAsDeleted(NOW.plusMinutes(2));
            }
            return repository.saveAndFlush(notification).getId();
        });
    }

    private Long saveNotification() {
        return transaction.execute(status -> repository.saveAndFlush(new Notification(
            userRepository.getReferenceById(userId), NotificationType.LIKE,
            "관심", "관심 도착", null, null, NOW)).getId());
    }
}
