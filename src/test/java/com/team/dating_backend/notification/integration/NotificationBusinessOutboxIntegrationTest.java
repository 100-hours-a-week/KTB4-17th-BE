package com.team.dating_backend.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.chat.service.ChatRoomCreateService;
import com.team.dating_backend.common.lock.repository.UserPairLockRepository;
import com.team.dating_backend.matching.enums.LikeStatus;
import com.team.dating_backend.matching.repository.LikeRepository;
import com.team.dating_backend.matching.service.LikeSendService;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationOutboxStatus;
import com.team.dating_backend.notification.exception.NotificationInvalidCreateRequestException;
import com.team.dating_backend.notification.outbox.NotificationRequestOutboxCodec;
import com.team.dating_backend.notification.repository.NotificationRepository;
import com.team.dating_backend.notification.repository.NotificationRequestOutboxRepository;
import com.team.dating_backend.notification.service.NotificationCreateRequestedValidator;
import com.team.dating_backend.notification.service.NotificationRequestOutboxService;
import com.team.dating_backend.notification.service.NotificationRequestService;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    {LikeSendService.class, ChatRoomCreateService.class, UserPairLockRepository.class,
        NotificationRequestService.class, NotificationRequestOutboxService.class,
        NotificationRequestOutboxCodec.class, NotificationCreateRequestedValidator.class}
)
@RecordApplicationEvents
@Testcontainers
class NotificationBusinessOutboxIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private LikeSendService service;
    @Autowired
    private LikeRepository likeRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private NotificationRequestOutboxRepository outboxRepository;
    @Autowired
    private NotificationRequestOutboxService outboxService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ApplicationEvents events;

    private TransactionTemplate transaction;
    private Long senderId;
    private Long receiverId;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        senderId = saveUser();
        receiverId = saveUser();
    }

    @AfterEach
    void cleanUp() {
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM notification_request_outbox");
            jdbc.update("DELETE FROM notifications");
            jdbc.update("DELETE FROM likes");
            jdbc.update("DELETE FROM user_pair_locks");
            jdbc.update("DELETE FROM users");
        });
    }

    @Test
    void 좋아요_commit_시_알림_요청을_PENDING으로_남기고_기존_요청_이벤트를_발행하지_않는다() {
        var response = service.sendLike(senderId, receiverId);

        assertThat(response.status()).isEqualTo(LikeStatus.PENDING);
        assertThat(likeRepository.findById(response.likeId()).orElseThrow().getStatus()).isEqualTo(LikeStatus.PENDING);
        assertThat(outboxRepository.findAll()).singleElement().satisfies(outbox -> {
            assertThat(outbox.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
            assertThat(outbox.getPublishedAt()).isNull();
            var request = outboxService.restore(outbox);
            assertThat(request.sourceEventId()).isEqualTo("LIKE_SENT:" + response.likeId());
            assertThat(request.recipientUserId()).isEqualTo(receiverId);
            assertThat(request.requestedAt()).isNotNull();
        });
        assertThat(notificationRepository.count()).isZero();
        assertThat(events.stream(NotificationCreateRequested.class)).isEmpty();
    }

    @Test
    void 좋아요_저장_뒤_업무가_rollback되면_Outbox도_남지_않는다() {
        var failure = new NotificationInvalidCreateRequestException();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.sendLike(senderId, receiverId);
            entityManager.flush();
            assertThat(likeRepository.count()).isEqualTo(1L);
            assertThat(outboxRepository.count()).isEqualTo(1L);
            throw failure;
        })).isSameAs(failure);

        assertThat(likeRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
        assertThat(notificationRepository.count()).isZero();
        assertThat(events.stream(NotificationCreateRequested.class)).isEmpty();
    }

    private Long saveUser() {
        return transaction.execute(status -> {
            var now = LocalDateTime.now();
            var user = User.create("회원", LocalDate.of(2000, 1, 1), Gender.FEMALE, now);
            user.activate(now);
            return userRepository.saveAndFlush(user).getId();
        });
    }
}
