package com.team.dating_backend.aisimulation.integration;

import com.team.dating_backend.aisimulation.alert.AiSimulationFailureReporter;
import com.team.dating_backend.aisimulation.config.AiSimulationAlertConfiguration;
import com.team.dating_backend.common.alert.DiscordWebhookSender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.context.annotation.Bean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;

import com.team.dating_backend.aisimulation.config.AiSimulationKafkaConfiguration;
import com.team.dating_backend.aisimulation.config.AiSimulationKafkaProperties;
import com.team.dating_backend.aisimulation.config.AiSimulationRetryConfiguration;
import com.team.dating_backend.aisimulation.entity.AiSimulationReport;
import com.team.dating_backend.aisimulation.entity.AiSimulationSession;
import com.team.dating_backend.aisimulation.enums.AiSimulationNotificationResult;
import com.team.dating_backend.aisimulation.kafka.AiSimulationFailureClassifier;
import com.team.dating_backend.aisimulation.kafka.AiSimulationCompletedNotificationConsumer;
import com.team.dating_backend.aisimulation.kafka.AiSimulationRecommendationConsumer;
import com.team.dating_backend.aisimulation.kafka.AiSimulationRequestNotificationConsumer;
import com.team.dating_backend.aisimulation.repository.AiSimulationReportRepository;
import com.team.dating_backend.aisimulation.repository.AiSimulationSessionRepository;
import com.team.dating_backend.aisimulation.service.AiSimulationEventFactory;
import com.team.dating_backend.aisimulation.service.AiSimulationPersistenceService;
import com.team.dating_backend.aisimulation.service.AiSimulationNotificationProcessor;
import com.team.dating_backend.aisimulation.service.AiSimulationNotificationService;
import com.team.dating_backend.aisimulation.service.AiSimulationRecommendationService;
import com.team.dating_backend.common.config.SchedulingConfig;
import com.team.dating_backend.common.kafka.config.KafkaProducerConfiguration;
import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.outbox.NotificationRequestOutboxCodec;
import com.team.dating_backend.notification.repository.NotificationRequestOutboxRepository;
import com.team.dating_backend.notification.service.NotificationCreateRequestedValidator;
import com.team.dating_backend.notification.service.NotificationRequestOutboxService;
import com.team.dating_backend.notification.service.NotificationRequestService;
import com.team.dating_backend.recommendation.repository.RecommendationItemRepository;
import com.team.dating_backend.recommendation.service.RecommendationBatchCreateService;
import com.team.dating_backend.recommendation.service.SimulationRecommendationService;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest(
    showSql = false, properties = {
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "spring.kafka.admin.auto-create=false",
        "app.ai-simulation.kafka.requested-topic=side-effect-requested",
        "app.ai-simulation.kafka.completed-topic=side-effect-completed",
        "app.ai-simulation.kafka.request-notification.enabled=true",
        "app.ai-simulation.kafka.recommendation.enabled=true",
        "app.ai-simulation.kafka.requester-notification.enabled=true",
        "app.ai-simulation.kafka.target-notification.enabled=true", "app.ai-simulation.kafka.retry.delay-ms=50",
        "app.ai-simulation.alert.enabled=true",
        "app.ai-simulation.alert.webhook-url=https://discord.com/api/webhooks/123/test-token",
        "app.ai-simulation.alert.max-messages-per-window=1000"
    }
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ImportAutoConfiguration(
    {KafkaAutoConfiguration.class, JacksonAutoConfiguration.class,
        TaskSchedulingAutoConfiguration.class}
)
@Import(
    {AiSimulationAlertConfiguration.class, AiSimulationFailureReporter.class,
        AiSimulationRequestNotificationConsumer.class, AiSimulationRecommendationConsumer.class,
        AiSimulationFailureClassifier.class,
        AiSimulationCompletedNotificationConsumer.class, AiSimulationNotificationProcessor.class,
        AiSimulationNotificationService.class, AiSimulationRecommendationService.class,
        AiSimulationPersistenceService.class,
        SimulationRecommendationService.class, RecommendationBatchCreateService.class,
        NotificationRequestService.class, NotificationRequestOutboxService.class,
        NotificationRequestOutboxCodec.class, NotificationCreateRequestedValidator.class,
        KafkaProducerConfiguration.class, AiSimulationKafkaConfiguration.class, AiSimulationRetryConfiguration.class,
        SchedulingConfig.class, AiSimulationSideEffectConsumerIntegrationTest.PropertiesConfiguration.class}
)
@EmbeddedKafka(
    partitions = 4, topics = {
        "side-effect-requested", "side-effect-completed",
        "side-effect-requested-request-notification-retry", "side-effect-requested-request-notification-dlt",
        "side-effect-requested-recommendation-retry", "side-effect-requested-recommendation-dlt",
        "side-effect-completed-requester-notification-retry", "side-effect-completed-requester-notification-dlt",
        "side-effect-completed-target-notification-retry", "side-effect-completed-target-notification-dlt"
    }, brokerProperties = "auto.create.topics.enable=false"
)
@Testcontainers
@DirtiesContext
@Timeout(60)
class AiSimulationSideEffectConsumerIntegrationTest {

    private static final String REQUESTED = "side-effect-requested";
    private static final String COMPLETED = "side-effect-completed";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 11, 7, 10, 5);
    private static final LocalDateTime FINISHED_AT = CREATED_AT.plusMinutes(1);

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @MockitoBean(name = "aiSimulationDiscordWebhookSender")
    private DiscordWebhookSender alertSender;

    @Autowired
    private KafkaTemplate<Object, Object> template;
    @Autowired
    private EmbeddedKafkaBroker broker;
    @Autowired
    private KafkaListenerEndpointRegistry registry;
    @Autowired
    private AiSimulationSessionRepository sessions;
    @Autowired
    private AiSimulationReportRepository reports;
    @Autowired
    private NotificationRequestOutboxRepository outboxes;
    @Autowired
    private NotificationRequestOutboxCodec codec;
    @Autowired
    private RecommendationItemRepository items;
    @Autowired
    private RecommendationBatchCreateService batches;
    @Autowired
    private UserRepository users;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JsonMapper mapper;
    @MockitoSpyBean
    private AiSimulationNotificationProcessor notifications;

    private final AiSimulationEventFactory events = new AiSimulationEventFactory();
    private Long requester;
    private Long target;
    private Long sessionId;

    @BeforeEach
    void setUp() {
        for (String table : List.of("notification_request_outbox", "recommendation_items", "recommendation_batches",
            "recommendation_passes", "user_blocks", "chat_participants", "chat_rooms", "matches", "profiles",
            "ai_simulation_reports", "ai_simulation_sessions", "users")) {
            jdbc.update("DELETE FROM " + table);
        }
        requester = user("요청자");
        target = user("상대방");
        jdbc.update(
            "INSERT INTO profiles (user_id, nickname, created_at, updated_at) VALUES (?, '요청자', NOW(6), NOW(6))",
            requester);
        var session = sessions.saveAndFlush(new AiSimulationSession(requester, target, CREATED_AT));
        sessionId = session.getId();
        reports.saveAndFlush(new AiSimulationReport(session, CREATED_AT));
    }

    @Test
    void 네_업무가_각자_처리하고_같은_사건을_재발행해도_기록과_내용을_유지한다() throws Exception {
        publish(REQUESTED);
        var originalItem = items.findAll().getFirst();
        var request = codec.decode(outboxes.findAll().getFirst().getPayload());
        assertThat(request.recipientUserId()).isEqualTo(target);
        assertThat(request.sourceEventId()).isEqualTo("AI_SIMULATION_REQUESTED:" + sessionId);
        assertThat(request.body()).isEqualTo("누군가 회원님과의 AI 소개팅을 시작했어요");
        assertThat(request.requestedAt()).isEqualTo(CREATED_AT.toInstant(ZoneOffset.UTC));
        publish(REQUESTED);
        complete();
        publish(COMPLETED);
        var originalPayloads = payloads();
        publish(COMPLETED);
        batches.refreshRecommendationBatch(target);

        assertThat(payloads()).hasSize(3).isEqualTo(originalPayloads);
        assertThat(
            outboxes.findAll().stream().filter(row -> row.getSourceEventId().startsWith("AI_SIMULATION_COMPLETED:")))
            .hasSize(2).allSatisfy(row -> {
                var requestBody = codec.decode(row.getPayload());
                assertThat(requestBody.requestedAt()).isEqualTo(FINISHED_AT.toInstant(ZoneOffset.UTC));
                assertThat(requestBody.targetId()).isEqualTo(sessionId);
            });
        assertThat(items.findAll()).singleElement().satisfies(item -> {
            assertThat(item.getId()).isEqualTo(originalItem.getId());
            assertThat(item.getCandidateUserId()).isEqualTo(requester);
            assertThat(item.getRankingOrder()).isEqualTo(originalItem.getRankingOrder());
            assertThat(item.isSimulationRequested()).isTrue();
        });
        assertThat(registry.getAllListenerContainers().stream()
            .filter(container -> container.getListenerId().endsWith("-dlt")))
            .hasSize(4).allSatisfy(container -> assertThat(container.isRunning()).isFalse());
    }

    @Test
    void 완료_이후_도착한_요청은_요청_알림만_생략하고_추천과_양쪽_완료_알림을_처리한다() throws Exception {
        complete();
        publish(REQUESTED);
        assertThat(outboxes.count()).isZero();
        assertThat(items.count()).isEqualTo(1);
        publish(COMPLETED);
        assertThat(outboxes.findAll()).hasSize(2)
            .allSatisfy(row -> assertThat(row.getSourceEventId()).startsWith("AI_SIMULATION_COMPLETED:"));
    }

    @Test
    void Outbox_커밋_후_재시도해도_중복을_정상_처리하고_다른_업무를_유지한다() throws Exception {
        var results = new CopyOnWriteArrayList<AiSimulationNotificationResult>();
        doAnswer(invocation -> {
            var result = (AiSimulationNotificationResult) invocation.callRealMethod();
            results.add(result);
            if (results.size() == 1) {
                throw new QueryTimeoutException(null);
            }
            return result;
        }).when(notifications).requested(sessionId);

        var sent = publish(REQUESTED);
        var retry = assertForwarded("request-notification", "retry", sent);
        assertCommitted("local-ai-simulation-request-notification-request-notification-retry", retry.topic(),
            retry.partition(), retry.offset() + 1);

        assertThat(results).containsExactly(AiSimulationNotificationResult.RECORDED,
            AiSimulationNotificationResult.ALREADY_RECORDED);
        assertThat(outboxes.count()).isEqualTo(1);
        assertThat(items.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pass", "block", "withdrawn", "left"})
    void 상대방_제외_정책은_요청자_완료_알림에_영향을_주지_않는다(String policy) throws Exception {
        switch (policy) {
            case "pass" -> jdbc.update("""
                INSERT INTO recommendation_passes (passer_user_id, passed_user_id, created_at) VALUES (?, ?, NOW(6))
                """, target, requester);
            case "block" -> jdbc.update("""
                INSERT INTO user_blocks (blocker_user_id, blocked_user_id, blocked_at) VALUES (?, ?, NOW(6))
                """, target, requester);
            case "withdrawn" -> jdbc.update("UPDATE users SET status='WITHDRAWN' WHERE id=?", target);
            case "left" -> {
                jdbc.update(
                    "INSERT INTO matches (id, sender_id, receiver_id, status, matched_at) VALUES (99, ?, ?, 'ACTIVE', NOW(6))",
                    requester, target);
                jdbc.update(
                    "INSERT INTO chat_rooms (id, match_id, status, created_at) VALUES (99, 99, 'ACTIVE', NOW(6))");
                jdbc.update(
                    "INSERT INTO chat_participants (chat_room_id, user_id, status, is_chat_notification) VALUES (99, ?, 'LEFT', TRUE), (99, ?, 'ACTIVE', TRUE)",
                    target, requester);
            }
            default -> throw new IllegalArgumentException(policy);
        }
        publish(REQUESTED);
        assertThat(outboxes.count()).isZero();
        assertThat(items.count()).isZero();
        complete();
        publish(COMPLETED);
        assertThat(outboxes.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getRecipientUserId()).isEqualTo(requester);
            assertThat(row.getSourceEventId()).isEqualTo("AI_SIMULATION_COMPLETED:" + sessionId);
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_passes", Long.class))
            .isEqualTo(policy.equals("pass") ? 1L : 0L);
    }

    @Test
    void 요청자_자신이_비활성이면_요청자_완료_알림만_생략한다() throws Exception {
        complete();
        jdbc.update("UPDATE users SET status='ONBOARDING' WHERE id=?", requester);
        publish(COMPLETED);
        assertThat(outboxes.findAll()).singleElement()
            .satisfies(row -> assertThat(row.getRecipientUserId()).isEqualTo(target));
    }

    @ParameterizedTest
    @ValueSource(strings = {"request-notification", "recommendation", "requester-notification", "target-notification"})
    void 실제_DB_오류는_해당_업무의_DLT로_전달하고_다른_업무와_생성_결과를_유지한다(String task) throws Exception {
        boolean requested = task.equals("request-notification") || task.equals("recommendation");
        if (!requested) {
            complete();
        }
        String topic = requested ? REQUESTED : COMPLETED;
        String table = task.equals("recommendation") ? "recommendation_items" : "notification_request_outbox";
        Long failedRecipient = task.equals("requester-notification") ? requester : target;
        String condition = task.equals("recommendation")
            ? "simulation_requested = FALSE"
            : "recipient_user_id <> " + failedRecipient;
        jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT test_reject_side_effect CHECK (" + condition + ")");
        Map<Long, String> healthyPayloads;
        try {
            var sent = publish(topic);
            assertForwarded(task, "dlt", sent);
            assertThat(outboxes.count()).isEqualTo(task.equals("request-notification") ? 0 : 1);
            assertThat(items.count()).isEqualTo(task.equals("request-notification") ? 1 : 0);
            healthyPayloads = payloads();
            assertThat(sessions.findById(sessionId).orElseThrow().getStatus().name())
                .isEqualTo(requested ? "IN_PROGRESS" : "COMPLETED");
            assertThat(jdbc.queryForObject("SELECT status FROM ai_simulation_reports WHERE session_id=?", String.class,
                sessionId))
                .isEqualTo(requested ? "GENERATING" : "COMPLETED");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(alertSender).send(any(), any(),
                org.mockito.ArgumentMatchers.argThat(fields -> sessionId.equals(fields.get("simulationId"))
                    && task.replace('-', '_').toUpperCase(java.util.Locale.ROOT).equals(fields.get("task"))
                    && fields.get("destinationOffset") != null)));
        } finally {
            jdbc.execute("ALTER TABLE " + table + " DROP CHECK test_reject_side_effect");
        }
        publish(topic);
        assertThat(payloads()).containsAllEntriesOf(healthyPayloads).hasSize(requested ? 1 : 2);
        assertThat(items.count()).isEqualTo(requested ? 1 : 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"IN_PROGRESS", "FAILED", "CANCELED"})
    void 완료되지_않은_세션의_완료_이벤트는_양쪽_모두_정상_소비하고_알림을_생성하지_않는다(String status) throws Exception {
        jdbc.update("UPDATE ai_simulation_sessions SET status=? WHERE id=?", status, sessionId);
        publish(COMPLETED);
        assertThat(outboxes.count()).isZero();
        assertThat(sessions.findById(sessionId).orElseThrow().getStatus().name()).isEqualTo(status);
    }

    private RecordMetadata publish(String topic) throws Exception {
        Object event = topic.equals(REQUESTED)
            ? events.requested(sessionId, CREATED_AT)
            : events.completed(sessionId, FINISHED_AT);
        var sent = template.send(topic, 3, sessionId, event).get(10, TimeUnit.SECONDS).getRecordMetadata();
        for (String task : topic.equals(REQUESTED)
            ? List.of("request-notification", "recommendation")
            : List.of("requester-notification", "target-notification")) {
            assertCommitted("local-ai-simulation-" + task, topic, sent.partition(), sent.offset() + 1);
        }
        return sent;
    }

    private void assertCommitted(String group, String topic, int partition, long expected) {
        try (var admin = Admin.create(Map.of("bootstrap.servers", broker.getBrokersAsString()))) {
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                var offset = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS).get(new TopicPartition(topic, partition));
                assertThat(offset).isNotNull();
                assertThat(offset.offset()).isGreaterThanOrEqualTo(expected);
            });
        }
    }

    private ConsumerRecord<Long, byte[]> assertForwarded(String task, String destination, RecordMetadata original) {
        String topic = original.topic() + "-" + task + "-" + destination;
        try (var consumer = new KafkaConsumer<Long, byte[]>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false), new LongDeserializer(), new ByteArrayDeserializer())) {
            consumer.assign(List.of(new TopicPartition(topic, original.partition())));
            consumer.seekToBeginning(consumer.assignment());
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(100))) {
                    if (sessionId.equals(record.key())) {
                        Object event = original.topic().equals(REQUESTED)
                            ? events.requested(sessionId, CREATED_AT)
                            : events.completed(sessionId, FINISHED_AT);
                        assertThat(mapper.readValue(record.value(), event.getClass())).isEqualTo(event);
                        assertThat(record.partition()).isEqualTo(original.partition());
                        return record;
                    }
                }
            }
            throw new AssertionError("Forwarded record was not published: " + task + "-" + destination);
        }
    }

    private void complete() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var session = sessions.findByIdForUpdate(sessionId).orElseThrow();
            session.complete(FINISHED_AT);
            var report = reports.findBySessionId(sessionId).orElseThrow();
            report.complete(78, "완료된 리포트", Map.of("summary", "완료된 리포트"), FINISHED_AT);
        });
    }

    private Map<Long, String> payloads() {
        return outboxes.findAll().stream().collect(Collectors.toMap(NotificationRequestOutbox::getId,
            NotificationRequestOutbox::getPayload));
    }

    private Long user(String name) {
        var user = User.create(name, LocalDate.of(2000, 1, 1), Gender.MALE, CREATED_AT);
        user.activate(CREATED_AT);
        return users.saveAndFlush(user).getId();
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiSimulationKafkaProperties.class)
    static class PropertiesConfiguration {

        @Bean
        SimpleMeterRegistry meters() {
            return new SimpleMeterRegistry();
        }
    }
}
