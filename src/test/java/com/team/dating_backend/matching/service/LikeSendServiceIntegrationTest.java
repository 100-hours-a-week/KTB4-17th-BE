package com.team.dating_backend.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.chat.service.ChatRoomCreateService;
import com.team.dating_backend.common.lock.repository.UserPairLockRepository;
import com.team.dating_backend.matching.dto.response.LikeCreateResponse;
import com.team.dating_backend.matching.entity.Like;
import com.team.dating_backend.matching.enums.LikeErrorCode;
import com.team.dating_backend.matching.enums.LikeStatus;
import com.team.dating_backend.matching.exception.LikeBusinessException;
import com.team.dating_backend.matching.repository.LikeRepository;
import com.team.dating_backend.notification.entity.Notification;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.repository.NotificationRepository;
import com.team.dating_backend.notification.support.NotificationRequestTestConfiguration;
import com.team.dating_backend.notification.support.NotificationRequestTestConfiguration.StoredRequests;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.repository.ProfileRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
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
@Import(
    {LikeSendService.class, ChatRoomCreateService.class,
        UserPairLockRepository.class, NotificationRequestTestConfiguration.class}
)
@Testcontainers
class LikeSendServiceIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 17, 12, 0);

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private LikeSendService service;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;
    private Long senderId;
    private Long receiverId;

    @Autowired
    private StoredRequests storedRequests;

    @BeforeEach
    void setUp() {
        storedRequests.clear();
        transaction = new TransactionTemplate(transactionManager);
        senderId = saveUser("봄날", Gender.MALE);
        receiverId = saveUser("여름", Gender.FEMALE);
    }

    @AfterEach
    void cleanUp() {
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM notifications");
            jdbc.update("DELETE FROM chat_participants");
            jdbc.update("DELETE FROM chat_rooms");
            jdbc.update("DELETE FROM matches");
            jdbc.update("DELETE FROM likes");
            jdbc.update("DELETE FROM user_pair_locks");
            jdbc.update("DELETE FROM profiles");
            jdbc.update("DELETE FROM users");
        });
    }

    @Test
    void 일반_좋아요는_닉네임_문구를_저장하고_닉네임_변경과_중복_요청에도_기존_알림을_유지한다() {
        LikeCreateResponse response = service.sendLike(senderId, receiverId);

        assertThat(response.status()).isEqualTo(LikeStatus.PENDING);
        assertThat(likeRepository.findById(response.likeId()).orElseThrow().getStatus())
            .isEqualTo(LikeStatus.PENDING);
        assertThat(notificationRepository.count()).isZero();
        assertThat(storedRequests.snapshot()).singleElement().satisfies(request -> {
            assertThat(request.sourceEventId()).isEqualTo("LIKE_SENT:" + response.likeId());
            assertThat(request.recipientUserId()).isEqualTo(receiverId);
        });
        var originalRequest = storedRequests.snapshot().getFirst();
        var originalOutboxes = jdbc.queryForList("SELECT * FROM notification_request_outbox ORDER BY id");
        assertThat(originalRequest.schemaVersion()).isEqualTo(NotificationCreateRequested.CURRENT_SCHEMA_VERSION);
        assertThat(originalRequest.type()).isEqualTo(NotificationType.LIKE);
        assertThat(originalRequest.title()).isEqualTo("새로운 좋아요");
        assertThat(originalRequest.body()).isEqualTo("봄날님이 당신에게 마음을 전했어요.");
        assertThat(originalRequest.targetType()).isEqualTo("PROFILE");
        assertThat(originalRequest.targetId()).isEqualTo(senderId);
        assertThat(originalRequest.requestedAt()).isNotNull();
        storedRequests.createNotificationsFromStoredRequests();
        List<Notification> notifications = notificationRepository.findAll();
        assertThat(notifications).hasSize(1);
        Notification notification = notifications.getFirst();
        assertThat(notification.getUserId()).isEqualTo(receiverId);
        assertThat(notification.getType()).isEqualTo(NotificationType.LIKE);
        assertThat(notification.getTitle()).isEqualTo("새로운 좋아요");
        assertThat(notification.getBody()).isEqualTo("봄날님이 당신에게 마음을 전했어요.");
        assertThat(notification.getTargetType()).isEqualTo("PROFILE");
        assertThat(notification.getTargetId()).isEqualTo(senderId);
        assertThat(notification.getReadAt()).isNull();
        assertThat(notification.getDeletedAt()).isNull();
        assertThat(notificationRepository.countVisibleUnreadByUserId(senderId)).isZero();
        assertThat(notificationRepository.countVisibleUnreadByUserId(receiverId)).isEqualTo(1L);
        assertThat(count("matches")).isZero();
        assertThat(count("chat_rooms")).isZero();
        Map<String, Object> before = notificationRow(notification.getId());

        transaction.executeWithoutResult(status -> profileRepository
            .findByUserIdAndDeletedAtIsNull(senderId).orElseThrow()
            .updateProfile(null, "가을", null, null, null, null, null, null, null, null));
        assertError(senderId, receiverId, LikeErrorCode.DUPLICATE_PENDING_LIKE);

        assertThat(notificationRow(notification.getId())).isEqualTo(before);
        assertThat(notificationRepository.count()).isEqualTo(1L);
        assertThat(likeRepository.count()).isEqualTo(1L);
        assertThat(storedRequests.snapshot()).containsExactly(originalRequest);
        assertThat(jdbc.queryForList("SELECT * FROM notification_request_outbox ORDER BY id"))
            .isEqualTo(originalOutboxes);
    }

    @Test
    void 상호_좋아요는_양쪽에_매칭_알림만_추가하고_기존_좋아요_알림을_보존한다() {
        service.sendLike(senderId, receiverId);
        storedRequests.createNotificationsFromStoredRequests();
        Long originalId = notificationRepository.findAll().getFirst().getId();
        transaction.executeWithoutResult(status -> notificationRepository.findById(originalId)
            .orElseThrow().markAsRead(NOW));
        Map<String, Object> before = notificationRow(originalId);

        assertThat(service.sendLike(receiverId, senderId).status()).isEqualTo(LikeStatus.MATCHED);
        storedRequests.createNotificationsFromStoredRequests();

        assertThat(likeRepository.findAll()).hasSize(2)
            .extracting(Like::getStatus).containsOnly(LikeStatus.MATCHED);
        assertThat(count("matches")).isEqualTo(1L);
        assertThat(count("chat_rooms")).isEqualTo(1L);
        assertThat(count("chat_participants")).isEqualTo(2L);
        assertMatchNotifications();
        assertThat(notificationRow(originalId)).isEqualTo(before);
        assertThat(notificationRepository.countVisibleUnreadByUserId(senderId)).isEqualTo(1L);
        assertThat(notificationRepository.countVisibleUnreadByUserId(receiverId)).isEqualTo(1L);
        assertThat(notificationRepository.findVisibleByUserId(receiverId, PageRequest.of(0, 20)))
            .hasSize(2);

        assertError(receiverId, senderId, LikeErrorCode.MATCH_ALREADY_EXISTS);
        assertThat(notificationRepository.count()).isEqualTo(3L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LIKE", "MATCH_FIRST", "MATCH_SECOND"})
    void 실제_Outbox_저장_실패시_신규_업무와_요청을_롤백하고_기존_데이터를_보존한다(String scenario) {
        boolean matching = !scenario.equals("LIKE");
        if (matching) {
            service.sendLike(senderId, receiverId);
            storedRequests.createNotificationsFromStoredRequests();
        }
        var requestsBefore = storedRequests.snapshot();
        var outboxesBefore = jdbc.queryForList("SELECT * FROM notification_request_outbox ORDER BY id");
        var likesBefore = jdbc.queryForList("SELECT * FROM likes ORDER BY id");
        var notificationsBefore = jdbc.queryForList("SELECT * FROM notifications ORDER BY id");
        NotificationType failingType = matching ? NotificationType.MATCH : NotificationType.LIKE;
        Long failingRecipient = scenario.equals("MATCH_FIRST") ? senderId : receiverId;
        jdbc.execute("""
            ALTER TABLE notification_request_outbox ADD CONSTRAINT test_reject_matching_outbox
            CHECK (type <> '%s' OR recipient_user_id <> %d)
            """.formatted(failingType.name(), failingRecipient));
        try {
            assertThatThrownBy(() -> service.sendLike(
                matching ? receiverId : senderId, matching ? senderId : receiverId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("test_reject_matching_outbox");

            assertThat(storedRequests.snapshot()).containsExactlyElementsOf(requestsBefore);
            assertThat(jdbc.queryForList("SELECT * FROM notification_request_outbox ORDER BY id"))
                .isEqualTo(outboxesBefore);
            assertThat(jdbc.queryForList("SELECT * FROM likes ORDER BY id")).isEqualTo(likesBefore);
            assertThat(jdbc.queryForList("SELECT * FROM notifications ORDER BY id")).isEqualTo(notificationsBefore);
            assertThat(count("matches")).isZero();
            assertThat(count("chat_rooms")).isZero();
            assertThat(count("chat_participants")).isZero();
        } finally {
            jdbc.execute("ALTER TABLE notification_request_outbox DROP CHECK test_reject_matching_outbox");
        }

        assertThat(service.sendLike(matching ? receiverId : senderId, matching ? senderId : receiverId).status())
            .isEqualTo(matching ? LikeStatus.MATCHED : LikeStatus.PENDING);
        assertThat(storedRequests.snapshot()).hasSize(matching ? 3 : 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 동시_좋아요는_같은_방향의_중복을_막고_상호_좋아요의_매칭_알림을_중복_생성하지_않는다(
        boolean reciprocal) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<String> outcomes;
        try {
            Future<String> first = executor.submit(() -> sendAfterStart(senderId, receiverId, ready, start));
            Future<String> second = executor.submit(() -> sendAfterStart(
                reciprocal ? receiverId : senderId, reciprocal ? senderId : receiverId, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            outcomes = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(notificationRepository.count()).isZero();
        storedRequests.createNotificationsFromStoredRequests();
        assertThat(storedRequests.snapshot()).hasSize(reciprocal ? 3 : 1);
        if (reciprocal) {
            assertThat(outcomes).containsExactlyInAnyOrder("PENDING", "MATCHED");
            assertThat(likeRepository.findAll()).hasSize(2)
                .extracting(Like::getStatus).containsOnly(LikeStatus.MATCHED);
            assertThat(count("matches")).isEqualTo(1L);
            assertThat(count("chat_rooms")).isEqualTo(1L);
            assertThat(count("chat_participants")).isEqualTo(2L);
            assertMatchNotifications();
        } else {
            assertThat(outcomes).containsExactlyInAnyOrder("PENDING", "DUPLICATE_PENDING_LIKE");
            assertThat(likeRepository.count()).isEqualTo(1L);
            assertThat(notificationRepository.findAll()).singleElement().satisfies(notification -> {
                assertThat(notification.getUserId()).isEqualTo(receiverId);
                assertThat(notification.getType()).isEqualTo(NotificationType.LIKE);
            });
            assertThat(count("matches")).isZero();
            assertThat(count("chat_rooms")).isZero();
        }
    }

    private void assertMatchNotifications() {
        var matchRequests = storedRequests.snapshot().stream()
            .filter(request -> request.type() == NotificationType.MATCH).toList();
        Long matchId = jdbc.queryForObject("SELECT id FROM matches", Long.class);
        assertThat(matchRequests).hasSize(2).extracting(NotificationCreateRequested::sourceEventId)
            .containsOnly("MATCH_CREATED:" + matchId);
        assertThat(matchRequests).extracting(NotificationCreateRequested::recipientUserId)
            .containsExactlyInAnyOrder(senderId, receiverId);
        assertThat(matchRequests).allSatisfy(request -> {
            assertThat(request.schemaVersion()).isEqualTo(NotificationCreateRequested.CURRENT_SCHEMA_VERSION);
            assertThat(request.title()).isEqualTo("매칭 성사");
            assertThat(request.body()).isEqualTo("서로의 마음이 연결되었어요. 채팅을 시작해 보세요.");
            assertThat(request.targetType()).isEqualTo("CHAT_ROOM");
            assertThat(request.requestedAt()).isNotNull();
        });
        assertThat(matchRequests.getFirst().requestedAt()).isEqualTo(matchRequests.getLast().requestedAt());
        List<Notification> notifications = notificationRepository.findAll();
        assertThat(notifications).hasSize(3);
        assertThat(notifications).filteredOn(notification -> notification.getType() == NotificationType.LIKE)
            .hasSize(1);
        List<Notification> matches = notifications.stream()
            .filter(notification -> notification.getType() == NotificationType.MATCH).toList();
        assertThat(matches).extracting(Notification::getUserId)
            .containsExactlyInAnyOrder(senderId, receiverId);
        Long roomId = jdbc.queryForObject("SELECT id FROM chat_rooms", Long.class);
        assertThat(matchRequests).extracting(NotificationCreateRequested::targetId).containsOnly(roomId);
        assertThat(matches).allSatisfy(notification -> {
            assertThat(notification.getTargetType()).isEqualTo("CHAT_ROOM");
            assertThat(notification.getTargetId()).isEqualTo(roomId);
            assertThat(notification.getReadAt()).isNull();
            assertThat(notification.getDeletedAt()).isNull();
        });
    }

    private String sendAfterStart(Long sender, Long receiver, CountDownLatch ready, CountDownLatch start)
        throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("동시 요청 시작 대기 초과");
        }
        try {
            return service.sendLike(sender, receiver).status().name();
        } catch (LikeBusinessException exception) {
            return exception.getErrorCode().name();
        }
    }

    private void assertError(Long sender, Long receiver, LikeErrorCode errorCode) {
        assertThatThrownBy(() -> service.sendLike(sender, receiver))
            .isInstanceOfSatisfying(LikeBusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }

    private Long saveUser(String nickname, Gender gender) {
        return transaction.execute(status -> {
            User user = User.create("회원", LocalDate.of(2000, 1, 1), gender, NOW);
            user.activate(NOW);
            userRepository.saveAndFlush(user);
            Profile profile = new Profile(user);
            profile.updateProfile(null, nickname, null, null, null, null, null, null, null, null);
            profileRepository.saveAndFlush(profile);
            return user.getId();
        });
    }

    private Map<String, Object> notificationRow(Long id) {
        return jdbc.queryForMap("SELECT * FROM notifications WHERE id = ?", id);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
