package com.team.dating_backend.aisimulation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.aisimulation.dto.event.AiSimulationRequestedEvent;
import com.team.dating_backend.aisimulation.entity.AiSimulationSession;
import com.team.dating_backend.aisimulation.enums.AiSimulationNotificationResult;
import com.team.dating_backend.aisimulation.kafka.AiSimulationRequestNotificationConsumer;
import com.team.dating_backend.aisimulation.repository.AiSimulationSessionRepository;
import com.team.dating_backend.notification.outbox.NotificationRequestOutboxCodec;
import com.team.dating_backend.notification.repository.NotificationRequestOutboxRepository;
import com.team.dating_backend.notification.service.NotificationCreateRequestedValidator;
import com.team.dating_backend.notification.service.NotificationRequestOutboxService;
import com.team.dating_backend.notification.service.NotificationRequestService;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(
    showSql = false, properties = {
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"
    }
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    {AiSimulationNotificationService.class, AiSimulationNotificationProcessor.class,
        AiSimulationRequestNotificationConsumer.class, NotificationRequestService.class,
        NotificationRequestOutboxService.class, NotificationRequestOutboxCodec.class,
        NotificationCreateRequestedValidator.class}
)
@Testcontainers
@Timeout(30)
class AiSimulationRequestNotificationIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 7, 10, 5);

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private AiSimulationRequestNotificationConsumer consumer;
    @Autowired
    private AiSimulationNotificationProcessor processor;
    @Autowired
    private AiSimulationSessionRepository sessions;
    @Autowired
    private NotificationRequestOutboxRepository outboxes;
    @Autowired
    private NotificationRequestOutboxCodec codec;
    @Autowired
    private UserRepository users;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long requester;
    private Long target;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM notification_request_outbox");
        jdbc.update("DELETE FROM ai_simulation_sessions");
        jdbc.update("DELETE FROM users");
        requester = user("요청자");
        target = user("상대방");
    }

    @Test
    void 같은_사건을_다시_받으면_기존_Outbox를_유지하고_Consumer는_정상_종료한다() {
        var session = session();
        var record = record(session);

        consumer.consume(record);
        var first = outboxes.findAll().getFirst();
        var payload = first.getPayload();
        assertThatCode(() -> consumer.consume(record)).doesNotThrowAnyException();

        assertThat(outboxes.findAll()).singleElement().satisfies(outbox -> {
            assertThat(outbox.getId()).isEqualTo(first.getId());
            assertThat(outbox.getPayload()).isEqualTo(payload);
            var request = codec.decode(outbox.getPayload());
            assertThat(request.sourceEventId()).isEqualTo("AI_SIMULATION_REQUESTED:" + session.getId());
            assertThat(request.recipientUserId()).isEqualTo(target);
            assertThat(request.requestedAt()).isEqualTo(NOW.toInstant(ZoneOffset.UTC));
        });
        assertThat(processor.requested(session.getId())).isEqualTo(AiSimulationNotificationResult.ALREADY_RECORDED);
    }

    @Test
    void 동시에_같은_사건을_처리해도_Outbox는_한개이고_중복은_정상_종료한다() throws Exception {
        var session = session();
        var gate = new CyclicBarrier(2);
        try (var calls = Executors.newFixedThreadPool(2)) {
            var first = calls.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return processor.requested(session.getId());
            });
            var second = calls.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return processor.requested(session.getId());
            });
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(AiSimulationNotificationResult.RECORDED,
                    AiSimulationNotificationResult.ALREADY_RECORDED);
        }
        assertThat(outboxes.count()).isEqualTo(1);
    }

    @Test
    void 생성_완료_트랜잭션과_경쟁하면_잠금_해제_후_완료_상태를_확인해_알림을_생략한다() throws Exception {
        var session = session();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var calls = Executors.newFixedThreadPool(2)) {
            var completion = calls.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                sessions.findByIdForUpdate(session.getId()).orElseThrow().complete(NOW.plusSeconds(1));
                locked.countDown();
                try {
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }));
            Future<AiSimulationNotificationResult> notification;
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                notification = calls.submit(() -> processor.requested(session.getId()));
                assertThatThrownBy(() -> notification.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            completion.get(10, TimeUnit.SECONDS);
            assertThat(notification.get(10, TimeUnit.SECONDS)).isEqualTo(AiSimulationNotificationResult.SKIPPED);
        }
        assertThat(outboxes.count()).isZero();
    }

    @Test
    void 이미_완료된_세션의_요청은_Consumer가_정상적으로_생략한다() {
        var session = new AiSimulationSession(requester, target, NOW);
        session.complete(NOW.plusSeconds(1));
        sessions.saveAndFlush(session);

        consumer.consume(record(session));

        assertThat(outboxes.count()).isZero();
    }

    @Test
    void 다른_DB_제약_위반은_중복으로_삼키지_않고_롤백_후_다음_요청을_허용한다() {
        var session = session();
        jdbc.execute(
            "ALTER TABLE notification_request_outbox ADD CONSTRAINT reject_request_notification CHECK (type <> 'AI_SIMULATION_REQUESTED')");
        try {
            assertThatThrownBy(() -> consumer.consume(record(session)))
                .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(outboxes.count()).isZero();
        } finally {
            jdbc.execute("ALTER TABLE notification_request_outbox DROP CHECK reject_request_notification");
        }
        consumer.consume(record(session));
        assertThat(outboxes.count()).isEqualTo(1);
    }

    @Test
    void 바깥_트랜잭션에서는_중복_처리기를_호출하지_못한다() {
        var session = session();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
            .executeWithoutResult(tx -> processor.requested(session.getId())))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(outboxes.count()).isZero();
    }

    private AiSimulationSession session() {
        return sessions.saveAndFlush(new AiSimulationSession(requester, target, NOW));
    }

    private Long user(String name) {
        var user = User.create(name, LocalDate.of(2000, 1, 1), Gender.FEMALE, NOW);
        user.activate(NOW);
        return users.saveAndFlush(user).getId();
    }

    private ConsumerRecord<Long, AiSimulationRequestedEvent> record(AiSimulationSession session) {
        return new ConsumerRecord<>("test-requested", 3, 42L, session.getId(),
            new AiSimulationEventFactory().requested(session.getId(), session.getCreatedAt()));
    }
}
