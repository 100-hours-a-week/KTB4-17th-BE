package com.team.dating_backend.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.notification.dto.event.NotificationCreatedEvent;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.dto.response.NotificationListResponse;
import com.team.dating_backend.notification.entity.Notification;
import com.team.dating_backend.notification.enums.NotificationCategory;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.service.NotificationAccessService;
import com.team.dating_backend.notification.service.NotificationDeleteService;
import com.team.dating_backend.notification.service.NotificationListService;
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
@Import({NotificationAccessService.class, NotificationDeleteService.class, NotificationListService.class})
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
    void 분리된_Entity에서도_회원_지연_로딩_없이_이벤트를_구성한다() {
        transaction.executeWithoutResult(status -> {
            Notification notification = repository.saveAndFlush(new Notification(
                userRepository.getReferenceById(userId), NotificationType.CHAT_MESSAGE,
                "채팅", "메시지 도착", "CHAT_ROOM", 7L, NOW));
            Long notificationId = notification.getId();
            entityManager.clear();
            Notification detached = repository.findById(notificationId).orElseThrow();
            assertThat(Hibernate.isInitialized(detached.getUser())).isFalse();
            entityManager.clear();

            NotificationCreatedEvent event = NotificationCreatedEvent.from(detached);

            assertThat(Hibernate.isInitialized(detached.getUser())).isFalse();
            assertThat(event.recipientUserId()).isEqualTo(userId);
            assertThat(event.notification()).isEqualTo(new NotificationEventResponse(
                notificationId, "CHAT_MESSAGE", "채팅", "메시지 도착", "CHAT_ROOM", 7L,
                null, NOW));
            detached.markAsRead(NOW.plusMinutes(1));
            assertThat(event.notification().readAt()).isNull();
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
    void 카테고리별로_본인_미삭제_알림만_ID_내림차순으로_조회한다(NotificationCategory category) {
        otherUserId = saveOtherUser();
        Map<NotificationType, Long> visibleIds = new EnumMap<>(NotificationType.class);
        int offset = 0;
        for (NotificationType type : NotificationType.values()) {
            visibleIds.put(type, saveNotification(userId, type, NOW.minusSeconds(offset++), false));
            saveNotification(userId, type, NOW, true);
            saveNotification(otherUserId, type, NOW, false);
        }
        List<NotificationType> expectedTypes = switch (category) {
            case ALL -> List.of(NotificationType.NOTICE, NotificationType.AI_SIMULATION_REPORT_COMPLETED,
                NotificationType.AI_PRACTICE_CHAT_COMPLETED, NotificationType.FACE_ANALYSIS_COMPLETED,
                NotificationType.PERSONA_TRAINING_COMPLETED, NotificationType.CHAT_MESSAGE,
                NotificationType.MATCH, NotificationType.LIKE);
            case INTEREST -> List.of(NotificationType.MATCH, NotificationType.LIKE);
            case CHAT -> List.of(NotificationType.CHAT_MESSAGE);
            case SYSTEM -> List.of(NotificationType.NOTICE, NotificationType.AI_SIMULATION_REPORT_COMPLETED,
                NotificationType.AI_PRACTICE_CHAT_COMPLETED, NotificationType.FACE_ANALYSIS_COMPLETED,
                NotificationType.PERSONA_TRAINING_COMPLETED);
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
    void 후속_페이지는_경계_중복_없이_이어지고_삭제된_ID도_커서로_사용한다(NotificationCategory category) {
        NotificationType type = switch (category) {
            case ALL, INTEREST -> NotificationType.LIKE;
            case CHAT -> NotificationType.CHAT_MESSAGE;
            case SYSTEM -> NotificationType.AI_PRACTICE_CHAT_COMPLETED;
        };
        Long oldest = saveNotification(userId, type, NOW, false);
        otherUserId = saveOtherUser();
        saveNotification(otherUserId, type, NOW, false);
        if (category != NotificationCategory.ALL) {
            NotificationType otherType = category == NotificationCategory.CHAT
                ? NotificationType.LIKE
                : NotificationType.CHAT_MESSAGE;
            saveNotification(userId, otherType, NOW, false);
        }
        Long deletedCursor = saveNotification(userId, type, NOW, true);
        Long newest = saveNotification(userId, type, NOW, false);

        NotificationListResponse first = listService.getNotifications(userId, category, null, 1);
        NotificationListResponse second = listService.getNotifications(
            userId, category, first.pageInfo().nextCursor(), 1);
        NotificationListResponse fromDeleted = listService.getNotifications(
            userId, category, deletedCursor, 20);

        assertThat(first.items()).extracting(NotificationListResponse.Item::notificationId)
            .containsExactly(newest);
        assertThat(first.pageInfo()).isEqualTo(new NotificationListResponse.PageInfo(newest, true));
        assertThat(second.items()).extracting(NotificationListResponse.Item::notificationId)
            .containsExactly(oldest);
        assertThat(second.pageInfo()).isEqualTo(new NotificationListResponse.PageInfo(null, false));
        assertThat(fromDeleted.items()).extracting(NotificationListResponse.Item::notificationId)
            .containsExactly(oldest);
    }

    @Test
    void 최대_크기_20건과_추가_한_건의_경계를_페이지_누락_없이_처리한다() {
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            ids.add(saveNotification(userId, NotificationType.NOTICE, NOW, false));
        }

        NotificationListResponse first = listService.getNotifications(
            userId, NotificationCategory.ALL, Long.MAX_VALUE, 20);
        NotificationListResponse second = listService.getNotifications(
            userId, NotificationCategory.ALL, first.pageInfo().nextCursor(), 20);

        assertThat(first.items()).extracting(NotificationListResponse.Item::notificationId)
            .containsExactlyElementsOf(ids.subList(1, 21).reversed());
        assertThat(first.pageInfo()).isEqualTo(new NotificationListResponse.PageInfo(ids.get(1), true));
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

        NotificationListResponse beyond = listService.getNotifications(userId, category, 1L, 20);

        assertThat(empty).isEqualTo(new NotificationListResponse(
            List.of(), new NotificationListResponse.PageInfo(null, false)));
        assertThat(beyond).isEqualTo(empty);
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
