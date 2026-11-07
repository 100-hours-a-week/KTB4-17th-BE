package com.team.dating_backend.aisimulation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.aisimulation.dto.event.AiSimulationCompletedEvent;
import com.team.dating_backend.aisimulation.entity.AiSimulationReport;
import com.team.dating_backend.aisimulation.entity.AiSimulationSession;
import com.team.dating_backend.aisimulation.enums.AiSimulationNotificationRecipient;
import com.team.dating_backend.aisimulation.enums.AiSimulationNotificationResult;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import com.team.dating_backend.aisimulation.kafka.AiSimulationCompletedNotificationConsumer;
import com.team.dating_backend.aisimulation.repository.AiSimulationReportRepository;
import com.team.dating_backend.aisimulation.repository.AiSimulationSessionRepository;
import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
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
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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
        AiSimulationCompletedNotificationConsumer.class, NotificationRequestService.class,
        NotificationRequestOutboxService.class, NotificationRequestOutboxCodec.class,
        NotificationCreateRequestedValidator.class}
)
@Testcontainers
@Timeout(30)
class AiSimulationCompletedNotificationIntegrationTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 11, 7, 10, 5);
    private static final LocalDateTime FINISHED_AT = CREATED_AT.plusMinutes(1);

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private AiSimulationCompletedNotificationConsumer consumer;
    @Autowired
    private AiSimulationNotificationProcessor processor;
    @Autowired
    private AiSimulationNotificationService notifications;
    @Autowired
    private AiSimulationSessionRepository sessions;
    @Autowired
    private AiSimulationReportRepository reports;
    @Autowired
    private NotificationRequestOutboxRepository outboxes;
    @Autowired
    private NotificationRequestOutboxCodec codec;
    @Autowired
    private UserRepository users;
    @Autowired
    private JdbcTemplate jdbc;

    private Long requester;
    private Long target;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM notification_request_outbox");
        jdbc.update("DELETE FROM ai_simulation_reports");
        jdbc.update("DELETE FROM ai_simulation_sessions");
        jdbc.update("DELETE FROM users");
        requester = user("요청자");
        target = user("상대방");
    }

    @Test
    void 수신자별_완료_알림은_재처리해도_기존_사건_ID와_내용을_유지한다() {
        var session = completedSession();
        for (var recipient : AiSimulationNotificationRecipient.values()) {
            consume(session.getId(), recipient);
        }
        var payloads = outboxes.findAll().stream().collect(Collectors.toMap(
            NotificationRequestOutbox::getRecipientUserId, NotificationRequestOutbox::getPayload));

        for (var recipient : AiSimulationNotificationRecipient.values()) {
            consume(session.getId(), recipient);
            assertThat(processor.completed(session.getId(), recipient))
                .isEqualTo(AiSimulationNotificationResult.ALREADY_RECORDED);
        }

        assertThat(outboxes.findAll()).hasSize(2).allSatisfy(outbox -> {
            assertThat(outbox.getPayload()).isEqualTo(payloads.get(outbox.getRecipientUserId()));
            var request = codec.decode(outbox.getPayload());
            String suffix = outbox.getRecipientUserId().equals(requester) ? "" : ":" + target;
            assertThat(request.sourceEventId()).isEqualTo("AI_SIMULATION_COMPLETED:" + session.getId() + suffix);
            assertThat(request.requestedAt()).isEqualTo(FINISHED_AT.toInstant(ZoneOffset.UTC));
            assertThat(request.targetId()).isEqualTo(session.getId());
            assertThat(request.title()).isEqualTo("시뮬레이션 완료");
            assertThat(request.body()).isEqualTo("두 사람의 대화 시뮬레이션과 분석 보고서가 준비됐어요.");
        });
    }

    @ParameterizedTest
    @EnumSource(AiSimulationNotificationRecipient.class)
    void 같은_수신자에_대한_동시_처리는_Outbox를_중복_생성하지_않는다(AiSimulationNotificationRecipient recipient)
        throws Exception {
        var session = completedSession();
        var gate = new CyclicBarrier(2);
        try (var calls = Executors.newFixedThreadPool(2)) {
            var first = calls.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return processor.completed(session.getId(), recipient);
            });
            var second = calls.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return processor.completed(session.getId(), recipient);
            });
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(AiSimulationNotificationResult.RECORDED,
                    AiSimulationNotificationResult.ALREADY_RECORDED);
        }
        assertThat(outboxes.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(AiSimulationNotificationRecipient.class)
    void 한쪽_기록이_실패해도_다른_수신자와_완료_결과를_유지한다(AiSimulationNotificationRecipient failed) {
        var session = completedSession();
        var other = failed == AiSimulationNotificationRecipient.REQUESTER
            ? AiSimulationNotificationRecipient.TARGET
            : AiSimulationNotificationRecipient.REQUESTER;
        Long failedId = failed == AiSimulationNotificationRecipient.REQUESTER ? requester : target;
        Long otherId = failed == AiSimulationNotificationRecipient.REQUESTER ? target : requester;
        jdbc.execute(("ALTER TABLE notification_request_outbox ADD CONSTRAINT reject_completion_recipient "
            + "CHECK (type <> 'AI_SIMULATION_REPORT_COMPLETED' OR recipient_user_id <> %d)").formatted(failedId));
        try {
            assertThatThrownBy(() -> consume(session.getId(), failed))
                .isInstanceOf(DataIntegrityViolationException.class);
            consume(session.getId(), other);

            assertThat(outboxes.findAll()).singleElement()
                .satisfies(outbox -> assertThat(outbox.getRecipientUserId()).isEqualTo(otherId));
            assertThat(sessions.findById(session.getId()).orElseThrow().getStatus())
                .isEqualTo(AiSimulationSessionStatus.COMPLETED);
            assertThat(jdbc.queryForObject("SELECT status FROM ai_simulation_reports WHERE session_id=?",
                String.class, session.getId())).isEqualTo("COMPLETED");
        } finally {
            jdbc.execute("ALTER TABLE notification_request_outbox DROP CHECK reject_completion_recipient");
        }
        consume(session.getId(), failed);
        assertThat(outboxes.count()).isEqualTo(2);
    }

    @Test
    void 기존_Worker에서_기록한_완료_알림도_Kafka_재처리에서_중복_생성하지_않는다() {
        var session = completedSession();
        notifications.completed(session.getId(), requester, target, requester);
        notifications.completed(session.getId(), requester, target, target);
        var before = outboxes.findAll().stream().map(NotificationRequestOutbox::getPayload).toList();

        for (var recipient : AiSimulationNotificationRecipient.values()) {
            consume(session.getId(), recipient);
        }

        assertThat(outboxes.findAll()).extracting(NotificationRequestOutbox::getPayload)
            .containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    void 요청자_자신의_비활성_상태는_요청자_완료_알림만_제외한다() {
        var session = completedSession();
        jdbc.update("UPDATE users SET status='ONBOARDING' WHERE id=?", requester);

        consume(session.getId(), AiSimulationNotificationRecipient.REQUESTER);
        consume(session.getId(), AiSimulationNotificationRecipient.TARGET);

        assertThat(outboxes.findAll()).singleElement()
            .satisfies(outbox -> assertThat(outbox.getRecipientUserId()).isEqualTo(target));
    }

    private AiSimulationSession completedSession() {
        var session = new AiSimulationSession(requester, target, CREATED_AT);
        session.complete(FINISHED_AT);
        session = sessions.saveAndFlush(session);
        var report = new AiSimulationReport(session, CREATED_AT);
        report.complete(78, "완료된 리포트", Map.of("summary", "완료된 리포트"), FINISHED_AT);
        reports.saveAndFlush(report);
        return session;
    }

    private Long user(String name) {
        var user = User.create(name, LocalDate.of(2000, 1, 1), Gender.FEMALE, CREATED_AT);
        user.activate(CREATED_AT);
        return users.saveAndFlush(user).getId();
    }

    private void consume(Long sessionId, AiSimulationNotificationRecipient recipient) {
        var event = new AiSimulationEventFactory().completed(sessionId, FINISHED_AT);
        var record = new ConsumerRecord<Long, AiSimulationCompletedEvent>("test-completed", 3, 42L, sessionId, event);
        if (recipient == AiSimulationNotificationRecipient.REQUESTER) {
            consumer.consumeForRequester(record);
        } else {
            consumer.consumeForTarget(record);
        }
    }
}
