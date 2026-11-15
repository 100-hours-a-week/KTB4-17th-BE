package com.team.dating_backend.aisimulation.integration;

import com.team.dating_backend.aisimulation.alert.AiSimulationFailureReporter;
import com.team.dating_backend.aisimulation.config.AiSimulationAlertConfiguration;
import com.team.dating_backend.common.alert.DiscordWebhookSender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.ResourceAccessException;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.TARGET_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.USER_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.aiResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeast;

import com.team.dating_backend.aisimulation.client.AiSimulationAiClient;
import com.team.dating_backend.aisimulation.config.AiSimulationKafkaConfiguration;
import com.team.dating_backend.aisimulation.config.AiSimulationKafkaProperties;
import com.team.dating_backend.aisimulation.config.AiSimulationKafkaTopicConfiguration;
import com.team.dating_backend.aisimulation.config.AiSimulationRetryConfiguration;
import com.team.dating_backend.aisimulation.dto.event.AiSimulationCompletedEvent;
import com.team.dating_backend.aisimulation.dto.event.AiSimulationRequestedEvent;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.kafka.AiSimulationFailureClassifier;
import com.team.dating_backend.aisimulation.kafka.AiSimulationGenerationConsumer;
import com.team.dating_backend.aisimulation.kafka.AiSimulationKafkaListeners;
import com.team.dating_backend.aisimulation.service.AiSimulationEventFactory;
import com.team.dating_backend.aisimulation.service.AiSimulationEventPublisher;
import com.team.dating_backend.aisimulation.service.AiSimulationGenerationService;
import com.team.dating_backend.aisimulation.service.AiSimulationPersistenceService;
import com.team.dating_backend.aisimulation.service.AiSimulationResponseValidator;
import com.team.dating_backend.common.config.SchedulingConfig;
import com.team.dating_backend.common.kafka.config.KafkaProducerConfiguration;
import java.time.Duration;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.retrytopic.RetryTopicHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest(
    showSql = false, properties = {
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.kafka.admin.auto-create=false",
        "app.ai-simulation.kafka.requested-topic=generation-test-requested",
        "app.ai-simulation.kafka.completed-topic=generation-test-completed",
        "app.ai-simulation.kafka.generation.group-id=generation-test",
        "app.ai-simulation.kafka.generation.enabled=true",
        "app.ai-simulation.kafka.partitions=4",
        "app.ai-simulation.kafka.concurrency=2",
        "app.ai-simulation.kafka.replication-factor=1",
        "app.ai-simulation.kafka.min-in-sync-replicas=1",
        "app.ai-simulation.kafka.retry.max-attempts=4",
        "app.ai-simulation.kafka.retry.concurrency=1",
        "app.ai-simulation.kafka.retry.delay-ms=50",
        "app.ai-simulation.alert.enabled=true",
        "app.ai-simulation.alert.webhook-url=https://discord.com/api/webhooks/123/test-token",
        "app.ai-simulation.alert.max-messages-per-window=1000",
        "app.ai-simulation.alert.queue-capacity=1",
        "app.ai-simulation.kafka.publish-result-timeout-ms=100",
        "KAFKA_PRODUCER_DELIVERY_TIMEOUT_MS=3000", "KAFKA_PRODUCER_REQUEST_TIMEOUT_MS=1000"
    }
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ImportAutoConfiguration(
    {KafkaAutoConfiguration.class, JacksonAutoConfiguration.class,
        TaskSchedulingAutoConfiguration.class}
)
@Import(
    {AiSimulationAlertConfiguration.class, AiSimulationFailureReporter.class,
        AiSimulationGenerationConsumer.class, AiSimulationFailureClassifier.class, AiSimulationGenerationService.class,
        AiSimulationPersistenceService.class, AiSimulationResponseValidator.class,
        AiSimulationEventPublisher.class, AiSimulationEventFactory.class,
        KafkaProducerConfiguration.class, AiSimulationKafkaConfiguration.class,
        AiSimulationRetryConfiguration.class, AiSimulationKafkaTopicConfiguration.class,
        SchedulingConfig.class, AiSimulationGenerationConsumerIntegrationTest.PropertiesConfiguration.class}
)
@EmbeddedKafka(
    partitions = 4, topics = {
        "generation-test-requested", "generation-test-completed",
        "generation-test-requested-generation-retry", "generation-test-requested-generation-dlt"
    }, brokerProperties = "auto.create.topics.enable=false"
)
@Testcontainers
@DirtiesContext
@Timeout(60)
class AiSimulationGenerationConsumerIntegrationTest {

    private static final String REQUESTED = "generation-test-requested";
    private static final String COMPLETED = "generation-test-completed";
    private static final String RETRY = REQUESTED + "-generation-retry";
    private static final String GROUP = "generation-test";

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @MockitoBean(name = "aiSimulationDiscordWebhookSender")
    private DiscordWebhookSender alertSender;

    @Autowired
    private SimpleMeterRegistry meters;

    @Autowired
    private AiSimulationPersistenceService persistence;

    @Autowired
    private AiSimulationEventFactory events;

    @Autowired
    private KafkaTemplate<Object, Object> template;

    @MockitoSpyBean(name = "kafkaTemplate")
    private KafkaTemplate<?, ?> faultTemplate;

    @Autowired
    @Qualifier("aiSimulationAlertExecutor")
    private ThreadPoolTaskExecutor alertExecutor;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private JsonMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private AiSimulationAiClient ai;

    @MockitoSpyBean
    private AiSimulationEventPublisher publisher;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM ai_simulation_messages");
        jdbc.update("DELETE FROM ai_simulation_reports");
        jdbc.update("DELETE FROM ai_simulation_sessions");
        when(ai.run(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return aiResponse();
        });
    }

    @AfterEach
    void awaitAlertsIdle() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(alertExecutor.getThreadPoolExecutor().getActiveCount()).isZero();
            assertThat(alertExecutor.getThreadPoolExecutor().getQueue()).isEmpty();
        });
    }

    @Test
    void 요청을_처리하고_같은_사건을_다시_받아도_AI없이_동일한_완료_이벤트를_발행한다() throws Exception {
        var session = persistence.begin(USER_ID, TARGET_ID);
        var requested = events.requested(session.getId(), session.getCreatedAt());

        assertCommitted(GROUP, send(requested));
        var first = records(COMPLETED, session.getId(), 1).getFirst();
        assertThat(mapper.readValue(first.value(), AiSimulationCompletedEvent.class))
            .isEqualTo(
                events.completed(session.getId(), persistence.loadGenerationState(session.getId()).finishedAt()));

        assertCommitted(GROUP, send(requested));

        var completed = records(COMPLETED, session.getId(), 2);
        assertThat(completed).hasSize(2);
        assertThat(completed.getLast().value()).isEqualTo(first.value());
        assertThat(completed).allSatisfy(record -> assertThat(record.key()).isEqualTo(session.getId()));
        assertThat(messageCount(session.getId())).isEqualTo(20);
        verify(ai, times(1)).run(any());
    }

    @Test
    void AI_처리가_끝나기_전에는_원본_처리_위치를_확정하지_않는다() throws Exception {
        var session = persistence.begin(USER_ID, TARGET_ID);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(ai.run(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            started.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return aiResponse();
        });
        var sent = send(events.requested(session.getId(), session.getCreatedAt()));
        try {
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            try (var admin = admin()) {
                await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                    var offset = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS).get(new TopicPartition(REQUESTED, 3));
                    assertThat(offset == null || offset.offset() <= sent.offset()).isTrue();
                });
            }
            verify(publisher, never()).publishCompleted(any(), any());
        } finally {
            release.countDown();
        }
        assertCommitted(GROUP, sent);
        assertThat(records(COMPLETED, session.getId(), 1)).hasSize(1);
    }

    @Test
    void 재시도_대상_오류는_Retry로_전달하고_동일한_요청으로_생성을_완료한다() throws Exception {
        var session = persistence.begin(USER_ID, TARGET_ID);
        var requested = events.requested(session.getId(), session.getCreatedAt());
        var attempts = new AtomicInteger();
        when(ai.run(any())).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() < 3) {
                throw new AiSimulationBusinessException(AiSimulationErrorCode.AI_SERVER_UNAVAILABLE,
                    new java.net.SocketTimeoutException());
            }
            return aiResponse();
        });

        assertCommitted(GROUP, send(requested));
        assertThat(records(COMPLETED, session.getId(), 1)).hasSize(1);

        var retries = records(RETRY, session.getId(), 2);
        assertThat(retries).hasSize(2).allSatisfy(record -> {
            assertThat(record.partition()).isEqualTo(3);
            assertThat(mapper.readValue(record.value(), AiSimulationRequestedEvent.class)).isEqualTo(requested);
        });
        assertRetryCommitted(retries.getLast());
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(messageCount(session.getId())).isEqualTo(20);
    }

    @Test
    void 결과_커밋_후_완료_전달이_실패하면_Retry에서_AI없이_완료_전달을_복구한다() throws Exception {
        var session = persistence.begin(USER_ID, TARGET_ID);
        var attempts = new AtomicInteger();
        doAnswer(invocation -> {
            assertThat(persistence.loadGenerationState(session.getId()).status())
                .isEqualTo(AiSimulationSessionStatus.COMPLETED);
            if (attempts.incrementAndGet() == 1) {
                throw new AiSimulationBusinessException(AiSimulationErrorCode.SIMULATION_EVENT_PUBLISH_FAILED,
                    new TimeoutException());
            }
            return invocation.callRealMethod();
        }).when(publisher).publishCompleted(eq(session.getId()), any());

        assertCommitted(GROUP, send(events.requested(session.getId(), session.getCreatedAt())));
        var completed = records(COMPLETED, session.getId(), 1).getFirst();

        assertRetryCommitted(records(RETRY, session.getId(), 1).getFirst());
        assertThat(mapper.readValue(completed.value(), AiSimulationCompletedEvent.class))
            .isEqualTo(
                events.completed(session.getId(), persistence.loadGenerationState(session.getId()).finishedAt()));
        assertThat(attempts.get()).isEqualTo(2);
        verify(ai, times(1)).run(any());
        assertThat(messageCount(session.getId())).isEqualTo(20);
    }

    @ParameterizedTest
    @EnumSource(value = AiSimulationSessionStatus.class, names = {"FAILED", "CANCELED"})
    void 종료된_세션의_요청은_생성과_완료_전달없이_처리를_끝낸다(AiSimulationSessionStatus status) throws Exception {
        var session = persistence.begin(USER_ID, TARGET_ID);
        jdbc.update("UPDATE ai_simulation_sessions SET status=?, finished_at=UTC_TIMESTAMP(6) WHERE id=?",
            status.name(), session.getId());

        assertCommitted(GROUP, send(events.requested(session.getId(), session.getCreatedAt())));

        verifyNoInteractions(ai);
        verify(publisher, never()).publishCompleted(any(), any());
        assertThat(messageCount(session.getId())).isZero();
        assertThat(persistence.loadGenerationState(session.getId()).status()).isEqualTo(status);
    }

    @Test
    void 생성_재시도_소진은_DLT_확인_후_세션과_리포트를_실패_처리한다() throws Exception {
        var session = persistence.begin(USER_ID, TARGET_ID);
        var requested = events.requested(session.getId(), session.getCreatedAt());
        when(ai.run(any())).thenThrow(new AiSimulationBusinessException(AiSimulationErrorCode.AI_SERVER_UNAVAILABLE));

        assertCommitted(GROUP, send(requested));
        var dlt = records(REQUESTED + "-generation-dlt", session.getId(), 1).getFirst();
        assertRetryCommitted(records(RETRY, session.getId(), 3).getLast());

        assertThat(mapper.readValue(dlt.value(), AiSimulationRequestedEvent.class)).isEqualTo(requested);
        assertThat(dlt.partition()).isEqualTo(3);
        assertThat(persistence.loadGenerationState(session.getId()).status())
            .isEqualTo(AiSimulationSessionStatus.FAILED);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_simulation_reports WHERE session_id=?",
            String.class, session.getId())).isEqualTo("FAILED");
        verify(ai, times(4)).run(any());
        assertThat(messageCount(session.getId())).isZero();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(alertSender).send(any(), any(),
            org.mockito.ArgumentMatchers.argThat(fields -> session.getId().equals(fields.get("simulationId"))
                && "GENERATION".equals(fields.get("task")) && fields.get("destinationOffset") != null)));
    }

    @Test
    void 완료_전달_재시도가_소진돼도_완료_결과를_유지하고_AI를_재호출하지_않는다() throws Exception {
        var session = persistence.begin(USER_ID, TARGET_ID);
        doThrow(new AiSimulationBusinessException(AiSimulationErrorCode.SIMULATION_EVENT_PUBLISH_FAILED,
            new TimeoutException())).when(publisher).publishCompleted(eq(session.getId()), any());

        assertCommitted(GROUP, send(events.requested(session.getId(), session.getCreatedAt())));
        records(REQUESTED + "-generation-dlt", session.getId(), 1);
        assertRetryCommitted(records(RETRY, session.getId(), 3).getLast());

        assertThat(persistence.loadGenerationState(session.getId()).status())
            .isEqualTo(AiSimulationSessionStatus.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_simulation_reports WHERE session_id=?",
            String.class, session.getId())).isEqualTo("COMPLETED");
        assertThat(messageCount(session.getId())).isEqualTo(20);
        verify(ai, times(1)).run(any());
        verify(publisher, times(4)).publishCompleted(eq(session.getId()), any());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(alertSender).send(any(), any(),
            org.mockito.ArgumentMatchers.argThat(fields -> session.getId().equals(fields.get("simulationId"))
                && "COMPLETION_PUBLISH".equals(fields.get("task")))));
    }

    @Test
    void 재시도_불가_생성_오류도_DLT_전달_후_실패_처리한다() throws Exception {
        var session = persistence.begin(USER_ID, TARGET_ID);
        when(ai.run(any())).thenThrow(new AiSimulationBusinessException(AiSimulationErrorCode.AI_REQUEST_REJECTED));

        assertCommitted(GROUP, send(events.requested(session.getId(), session.getCreatedAt())));
        records(REQUESTED + "-generation-dlt", session.getId(), 1);

        assertThat(persistence.loadGenerationState(session.getId()).status())
            .isEqualTo(AiSimulationSessionStatus.FAILED);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_simulation_reports WHERE session_id=?",
            String.class, session.getId())).isEqualTo("FAILED");
        verify(ai, times(1)).run(any());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(alertSender).send(any(), any(),
            org.mockito.ArgumentMatchers.argThat(fields -> session.getId().equals(fields.get("simulationId")))));
    }

    private RecordMetadata send(AiSimulationRequestedEvent event) throws Exception {
        return template.send(REQUESTED, 3, event.simulationId(), event).get(10, TimeUnit.SECONDS).getRecordMetadata();
    }

    @Test
    void Discord_실패는_DLT와_최종_상태와_입력_commit을_유지하고_AI를_재실행하지_않는다() throws Exception {
        var session = persistence.begin(USER_ID, TARGET_ID);
        when(ai.run(any())).thenThrow(new AiSimulationBusinessException(AiSimulationErrorCode.AI_REQUEST_REJECTED));
        doThrow(new ResourceAccessException("private-webhook-token")).when(alertSender).send(any(), any(), any());
        double before = meters.find("ai.simulation.alert.failures").tag("error_code", "ALERT_WEBHOOK_FAILED")
            .counter() == null
                ? 0
                : meters.get("ai.simulation.alert.failures").tag("error_code", "ALERT_WEBHOOK_FAILED")
                    .counter().count();

        assertCommitted(GROUP, send(events.requested(session.getId(), session.getCreatedAt())));
        records(REQUESTED + "-generation-dlt", session.getId(), 1);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(meters.get("ai.simulation.alert.failures")
            .tag("error_code", "ALERT_WEBHOOK_FAILED").counter().count()).isEqualTo(before + 1));
        assertThat(persistence.loadGenerationState(session.getId()).status())
            .isEqualTo(AiSimulationSessionStatus.FAILED);
        verify(ai, times(1)).run(any());
        verify(alertSender, times(1)).send(any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(HandoffStage.class)
    void 전달_실패는_해당_입력_offset과_생성중_상태를_유지하고_복구_후_동일_사건을_DLT로_전달한다(HandoffStage stage)
        throws Exception {
        var session = persistence.begin(USER_ID, TARGET_ID);
        var requested = events.requested(session.getId(), session.getCreatedAt());
        when(ai.run(any())).thenThrow(new AiSimulationBusinessException(stage == HandoffStage.MAIN_DLT
            ? AiSimulationErrorCode.AI_REQUEST_REJECTED
            : AiSimulationErrorCode.AI_SERVER_UNAVAILABLE));
        String destination = REQUESTED + "-generation-" + (stage.dlt ? "dlt" : "retry");
        var reject = new AtomicBoolean(true);
        var rejected = new CountDownLatch(1);
        String code = stage.dlt ? "DLT_PUBLISH_FAILED" : "RETRY_PUBLISH_FAILED";
        double before = failureCount("ai.simulation.failures", code);
        doAnswer(invocation -> {
            ProducerRecord<?, ?> record = invocation.getArgument(0);
            if (record.topic().equals(destination) && reject.get()
                && ByteBuffer.wrap(record.headers().lastHeader(RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS).value())
                    .getInt() == stage.attempt + 1) {
                rejected.countDown();
                return CompletableFuture.failedFuture(new TimeoutException());
            }
            return invocation.callRealMethod();
        }).when(faultTemplate).send(any(ProducerRecord.class));
        var sent = send(requested);
        String inputTopic = stage.retryInput ? RETRY : REQUESTED;
        String inputGroup = stage.retryInput ? GROUP + "-generation-retry" : GROUP;
        long inputOffset;
        try {
            assertThat(rejected.await(15, TimeUnit.SECONDS)).isTrue();
            inputOffset = stage.retryInput
                ? records(RETRY, session.getId(), stage.attempt - 1).getLast().offset()
                : sent.offset();
            await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(failureCount("ai.simulation.failures", code)).isGreaterThan(before));
            assertNotCommitted(inputGroup, inputTopic, inputOffset);
            assertThat(persistence.loadGenerationState(session.getId()).status())
                .isEqualTo(AiSimulationSessionStatus.IN_PROGRESS);
            assertThat(jdbc.queryForObject("SELECT status FROM ai_simulation_reports WHERE session_id=?",
                String.class, session.getId())).isEqualTo("GENERATING");
            verifyNoInteractions(alertSender);
        } finally {
            reject.set(false);
        }
        assertCommitted(inputGroup, new TopicPartition(inputTopic, 3), inputOffset + 1);
        var dlt = records(REQUESTED + "-generation-dlt", session.getId(), 1).getFirst();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(
            persistence.loadGenerationState(session.getId()).status()).isEqualTo(AiSimulationSessionStatus.FAILED));
        assertThat(mapper.readValue(dlt.value(), AiSimulationRequestedEvent.class)).isEqualTo(requested);
        assertThat(dlt.partition()).isEqualTo(sent.partition());
        verify(ai, atLeast(stage == HandoffStage.MAIN_DLT ? 2 : 5)).run(any());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(alertSender).send(any(), any(), any()));
    }

    @Test
    void 전달_결과_확인_타임아웃은_offset을_확정하지_않고_다음_처리에서_복구한다() throws Exception {
        var session = persistence.begin(USER_ID, TARGET_ID);
        when(ai.run(any())).thenThrow(new AiSimulationBusinessException(AiSimulationErrorCode.AI_REQUEST_REJECTED));
        var pending = new AtomicBoolean(true);
        var entered = new CountDownLatch(1);
        double before = failureCount("ai.simulation.failures", "DLT_PUBLISH_FAILED");
        doAnswer(invocation -> {
            ProducerRecord<?, ?> record = invocation.getArgument(0);
            if (record.topic().equals(REQUESTED + "-generation-dlt") && pending.getAndSet(false)) {
                entered.countDown();
                return new CompletableFuture<SendResult<Object, Object>>();
            }
            return invocation.callRealMethod();
        }).when(faultTemplate).send(any(ProducerRecord.class));

        var sent = send(events.requested(session.getId(), session.getCreatedAt()));

        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertNotCommitted(GROUP, REQUESTED, sent.offset());
        assertThat(persistence.loadGenerationState(session.getId()).status())
            .isEqualTo(AiSimulationSessionStatus.IN_PROGRESS);
        verifyNoInteractions(alertSender);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(
            () -> assertThat(failureCount("ai.simulation.failures", "DLT_PUBLISH_FAILED")).isGreaterThan(before));
        assertCommitted(GROUP, sent);
        records(REQUESTED + "-generation-dlt", session.getId(), 1);
        assertThat(persistence.loadGenerationState(session.getId()).status())
            .isEqualTo(AiSimulationSessionStatus.FAILED);
        verify(ai, atLeast(2)).run(any());
    }

    @Test
    void DLT_이후_실패_상태_저장_오류는_DB와_offset을_유지하고_재처리에서_복구한다() throws Exception {
        var session = persistence.begin(USER_ID, TARGET_ID);
        var requested = events.requested(session.getId(), session.getCreatedAt());
        when(ai.run(any())).thenThrow(new AiSimulationBusinessException(AiSimulationErrorCode.AI_REQUEST_REJECTED));
        double before = failureCount("ai.simulation.failures", "FINAL_STATE_UPDATE_FAILED");
        jdbc.execute("ALTER TABLE ai_simulation_reports ADD CONSTRAINT reject_dlt_failure CHECK (status <> 'FAILED')");
        RecordMetadata sent;
        try {
            sent = send(requested);
            recordsAtLeast(REQUESTED + "-generation-dlt", session.getId(), 1);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                failureCount("ai.simulation.failures", "FINAL_STATE_UPDATE_FAILED")).isGreaterThan(before));
            assertNotCommitted(GROUP, REQUESTED, sent.offset());
            var state = persistence.loadGenerationState(session.getId());
            assertThat(state.status()).isEqualTo(AiSimulationSessionStatus.IN_PROGRESS);
            assertThat(state.finishedAt()).isNull();
            assertThat(jdbc.queryForObject("SELECT status FROM ai_simulation_reports WHERE session_id=?",
                String.class, session.getId())).isEqualTo("GENERATING");
            verifyNoInteractions(alertSender);
        } finally {
            jdbc.execute("ALTER TABLE ai_simulation_reports DROP CHECK reject_dlt_failure");
        }
        assertCommitted(GROUP, sent);
        assertThat(recordsAtLeast(REQUESTED + "-generation-dlt", session.getId(), 2)).allSatisfy(record -> {
            assertThat(mapper.readValue(record.value(), AiSimulationRequestedEvent.class)).isEqualTo(requested);
            assertThat(record.partition()).isEqualTo(3);
        });
        assertThat(persistence.loadGenerationState(session.getId()).status())
            .isEqualTo(AiSimulationSessionStatus.FAILED);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_simulation_reports WHERE session_id=?",
            String.class, session.getId())).isEqualTo("FAILED");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(alertSender).send(any(), any(), any()));
    }

    @Test
    void Discord_지연과_큐_포화는_Kafka_업무를_막거나_재실행하지_않는다() throws Exception {
        var sessions = IntStream.range(0, 3).mapToObj(index -> persistence.begin(USER_ID, TARGET_ID)).toList();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(ai.run(any())).thenThrow(new AiSimulationBusinessException(AiSimulationErrorCode.AI_REQUEST_REJECTED));
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(alertSender).send(any(), any(), any());
        double before = failureCount("ai.simulation.alert.failures", "ALERT_SUBMISSION_REJECTED");
        try {
            var first = sessions.getFirst();
            assertCommitted(GROUP, send(events.requested(first.getId(), first.getCreatedAt())));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            for (var session : sessions.subList(1, 3)) {
                assertCommitted(GROUP, send(events.requested(session.getId(), session.getCreatedAt())));
            }
            assertThat(failureCount("ai.simulation.alert.failures", "ALERT_SUBMISSION_REJECTED")).isEqualTo(before + 1);
            assertThat(alertExecutor.getThreadPoolExecutor().getQueue()).hasSize(1);
            for (var session : sessions) {
                assertThat(persistence.loadGenerationState(session.getId()).status())
                    .isEqualTo(AiSimulationSessionStatus.FAILED);
                records(REQUESTED + "-generation-dlt", session.getId(), 1);
            }
            verify(ai, times(3)).run(any());
        } finally {
            release.countDown();
        }
        awaitAlertsIdle();
        verify(alertSender, times(2)).send(any(), any(), any());
        verify(ai, times(3)).run(any());
    }

    private void assertNotCommitted(String group, String topic, long inputOffset) {
        try (var admin = admin()) {
            await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                var offset = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS).get(new TopicPartition(topic, 3));
                assertThat(offset == null || offset.offset() <= inputOffset).isTrue();
            });
        }
    }

    private double failureCount(String name, String code) {
        var counter = meters.find(name).tag("error_code", code).counter();
        return counter == null ? 0 : counter.count();
    }

    private enum HandoffStage {
        MAIN_RETRY(1, false, false),
        MAIN_DLT(1, false, true),
        RETRY_RETRY(2, true, false),
        RETRY_DLT(4, true, true);

        private final int attempt;
        private final boolean retryInput;
        private final boolean dlt;

        HandoffStage(int attempt, boolean retryInput, boolean dlt) {
            this.attempt = attempt;
            this.retryInput = retryInput;
            this.dlt = dlt;
        }
    }

    private List<ConsumerRecord<Long, byte[]>> records(String topic, Long id, int expected) {
        var result = recordsAtLeast(topic, id, expected);
        assertThat(result).hasSize(expected);
        return result;
    }

    private List<ConsumerRecord<Long, byte[]>> recordsAtLeast(String topic, Long id, int expected) {
        try (var consumer = new KafkaConsumer<Long, byte[]>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false), new LongDeserializer(), new ByteArrayDeserializer())) {
            consumer.assign(IntStream.range(0, 4)
                .mapToObj(partition -> new TopicPartition(topic, partition)).toList());
            consumer.seekToBeginning(consumer.assignment());
            var result = new ArrayList<ConsumerRecord<Long, byte[]>>();
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (result.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(100)).forEach(record -> {
                    if (id.equals(record.key())) {
                        result.add(record);
                    }
                });
            }
            assertThat(result).hasSizeGreaterThanOrEqualTo(expected);
            return result;
        }
    }

    private void assertCommitted(String group, RecordMetadata record) {
        assertCommitted(group, new TopicPartition(record.topic(), record.partition()), record.offset() + 1);
    }

    private void assertRetryCommitted(ConsumerRecord<Long, byte[]> record) {
        assertCommitted(GROUP + "-generation-retry", new TopicPartition(record.topic(), record.partition()),
            record.offset() + 1);
        assertThat(registry.getListenerContainer(AiSimulationKafkaListeners.GENERATION + "-generation-dlt").isRunning())
            .isFalse();
    }

    private void assertCommitted(String group, TopicPartition partition, long expected) {
        try (var admin = admin()) {
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                var offset = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS).get(partition);
                assertThat(offset).isNotNull();
                assertThat(offset.offset()).isGreaterThanOrEqualTo(expected);
            });
        }
    }

    private Admin admin() {
        return Admin.create(Map.of("bootstrap.servers", broker.getBrokersAsString()));
    }

    private int messageCount(Long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ai_simulation_messages WHERE session_id=?", Integer.class, id);
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
