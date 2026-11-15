package com.team.dating_backend.aisimulation.integration;

import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.aiResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.aisimulation.alert.AiSimulationFailureReporter;
import com.team.dating_backend.aisimulation.client.AiSimulationAiClient;
import com.team.dating_backend.aisimulation.config.AiSimulationAlertConfiguration;
import com.team.dating_backend.aisimulation.config.AiSimulationKafkaConfiguration;
import com.team.dating_backend.aisimulation.config.AiSimulationKafkaProperties;
import com.team.dating_backend.aisimulation.config.AiSimulationRetryConfiguration;
import com.team.dating_backend.aisimulation.controller.AiSimulationController;
import com.team.dating_backend.aisimulation.controller.AiSimulationExceptionHandler;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.PersonaBrief;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationRequest;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationResponse;
import com.team.dating_backend.aisimulation.dto.event.AiSimulationCompletedEvent;
import com.team.dating_backend.aisimulation.dto.event.AiSimulationRequestedEvent;
import com.team.dating_backend.aisimulation.entity.AiSimulationSession;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.enums.AiSimulationNotificationResult;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import com.team.dating_backend.aisimulation.kafka.AiSimulationCompletedNotificationConsumer;
import com.team.dating_backend.aisimulation.kafka.AiSimulationFailureClassifier;
import com.team.dating_backend.aisimulation.kafka.AiSimulationGenerationConsumer;
import com.team.dating_backend.aisimulation.kafka.AiSimulationKafkaListeners;
import com.team.dating_backend.aisimulation.kafka.AiSimulationRecommendationConsumer;
import com.team.dating_backend.aisimulation.kafka.AiSimulationRequestNotificationConsumer;
import com.team.dating_backend.aisimulation.repository.AiSimulationSessionRepository;
import com.team.dating_backend.aisimulation.service.AiSimulationAdmissionService;
import com.team.dating_backend.aisimulation.service.AiSimulationEventFactory;
import com.team.dating_backend.aisimulation.service.AiSimulationEventPublisher;
import com.team.dating_backend.aisimulation.service.AiSimulationGenerationService;
import com.team.dating_backend.aisimulation.service.AiSimulationNotificationProcessor;
import com.team.dating_backend.aisimulation.service.AiSimulationNotificationService;
import com.team.dating_backend.aisimulation.service.AiSimulationPersistenceService;
import com.team.dating_backend.aisimulation.service.AiSimulationQueryService;
import com.team.dating_backend.aisimulation.service.AiSimulationRecommendationService;
import com.team.dating_backend.aisimulation.service.AiSimulationResponseMapper;
import com.team.dating_backend.aisimulation.service.AiSimulationResponseValidator;
import com.team.dating_backend.aisimulation.service.AiSimulationService;
import com.team.dating_backend.aisimulation.service.AiSimulationTargetValidator;
import com.team.dating_backend.common.alert.DiscordWebhookSender;
import com.team.dating_backend.common.config.SchedulingConfig;
import com.team.dating_backend.common.exception.GlobalExceptionHandler;
import com.team.dating_backend.common.kafka.config.KafkaProducerConfiguration;
import com.team.dating_backend.common.lock.repository.UserPairLockRepository;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.outbox.NotificationRequestOutboxCodec;
import com.team.dating_backend.notification.repository.NotificationRequestOutboxRepository;
import com.team.dating_backend.notification.service.NotificationCreateRequestedValidator;
import com.team.dating_backend.notification.service.NotificationRequestOutboxService;
import com.team.dating_backend.notification.service.NotificationRequestService;
import com.team.dating_backend.recommendation.service.RecommendationBatchCreateService;
import com.team.dating_backend.recommendation.service.SimulationRecommendationService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest(
    showSql = false, properties = {
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "spring.kafka.admin.auto-create=false",
        "app.ai-simulation.kafka.requested-topic=flow-test-requested",
        "app.ai-simulation.kafka.completed-topic=flow-test-completed",
        "app.ai-simulation.kafka.generation.enabled=true",
        "app.ai-simulation.kafka.request-notification.enabled=true",
        "app.ai-simulation.kafka.recommendation.enabled=true",
        "app.ai-simulation.kafka.requester-notification.enabled=true",
        "app.ai-simulation.kafka.target-notification.enabled=true",
        "app.ai-simulation.kafka.generation.group-id=flow-test-generation",
        "app.ai-simulation.kafka.request-notification.group-id=flow-test-request-notification",
        "app.ai-simulation.kafka.recommendation.group-id=flow-test-recommendation",
        "app.ai-simulation.kafka.requester-notification.group-id=flow-test-requester-notification",
        "app.ai-simulation.kafka.target-notification.group-id=flow-test-target-notification",
        "app.ai-simulation.kafka.partitions=4", "app.ai-simulation.kafka.concurrency=2",
        "app.ai-simulation.kafka.replication-factor=1", "app.ai-simulation.kafka.min-in-sync-replicas=1",
        "app.ai-simulation.kafka.publish-result-timeout-ms=5000",
        "app.ai-simulation.kafka.retry.max-attempts=4", "app.ai-simulation.kafka.retry.delay-ms=50",
        "app.ai-simulation.alert.enabled=false"
    }
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ImportAutoConfiguration(
    {KafkaAutoConfiguration.class, JacksonAutoConfiguration.class,
        TaskSchedulingAutoConfiguration.class}
)
@Import(
    {AiSimulationService.class, AiSimulationAdmissionService.class, AiSimulationTargetValidator.class,
        UserPairLockRepository.class, AiSimulationQueryService.class, AiSimulationResponseMapper.class,
        AiSimulationResponseValidator.class, AiSimulationPersistenceService.class, AiSimulationGenerationService.class,
        AiSimulationEventPublisher.class, AiSimulationEventFactory.class, AiSimulationGenerationConsumer.class,
        AiSimulationFailureClassifier.class,
        AiSimulationRequestNotificationConsumer.class, AiSimulationRecommendationConsumer.class,
        AiSimulationCompletedNotificationConsumer.class, AiSimulationNotificationProcessor.class,
        AiSimulationNotificationService.class, AiSimulationRecommendationService.class,
        SimulationRecommendationService.class, RecommendationBatchCreateService.class,
        NotificationRequestService.class, NotificationRequestOutboxService.class, NotificationRequestOutboxCodec.class,
        NotificationCreateRequestedValidator.class, KafkaProducerConfiguration.class,
        AiSimulationKafkaConfiguration.class,
        AiSimulationRetryConfiguration.class, AiSimulationAlertConfiguration.class, AiSimulationFailureReporter.class,
        SchedulingConfig.class, AiSimulationKafkaFlowIntegrationTest.Config.class}
)
@EmbeddedKafka(
    partitions = 4, topics = {
        "flow-test-requested", "flow-test-completed",
        "flow-test-requested-generation-retry", "flow-test-requested-generation-dlt",
        "flow-test-requested-request-notification-retry", "flow-test-requested-request-notification-dlt",
        "flow-test-requested-recommendation-retry", "flow-test-requested-recommendation-dlt",
        "flow-test-completed-requester-notification-retry", "flow-test-completed-requester-notification-dlt",
        "flow-test-completed-target-notification-retry", "flow-test-completed-target-notification-dlt"
    }, brokerProperties = "auto.create.topics.enable=false"
)
@Testcontainers
@DirtiesContext
@Timeout(90)
class AiSimulationKafkaFlowIntegrationTest {

    private static final String REQUESTED = "flow-test-requested";
    private static final String COMPLETED = "flow-test-completed";

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private AiSimulationService service;
    @Autowired
    private AiSimulationQueryService query;
    @Autowired
    private AiSimulationPersistenceService persistence;
    @Autowired
    private AiSimulationSessionRepository sessions;
    @Autowired
    private NotificationRequestOutboxRepository outboxes;
    @Autowired
    private NotificationRequestOutboxCodec codec;
    @Autowired
    private AiSimulationEventFactory events;
    @Autowired
    private AiSimulationEventPublisher publisher;
    @Autowired
    private AiSimulationKafkaProperties properties;
    @Autowired
    private KafkaTemplate<Object, Object> template;
    @Autowired
    private EmbeddedKafkaBroker broker;
    @Autowired
    private KafkaListenerEndpointRegistry registry;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JsonMapper mapper;
    @MockitoBean
    private AiSimulationAiClient ai;
    @MockitoBean(name = "aiSimulationDiscordWebhookSender")
    private DiscordWebhookSender discord;
    @MockitoSpyBean(name = "kafkaTemplate")
    private KafkaTemplate<?, ?> faultTemplate;
    @MockitoSpyBean
    private AiSimulationNotificationProcessor notifications;

    private final CountDownLatch aiStarted = new CountDownLatch(1);
    private final CountDownLatch releaseAi = new CountDownLatch(1);
    private boolean consumersStopped;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        for (String table : List.of("notification_request_outbox", "notifications", "ai_simulation_messages",
            "ai_simulation_reports", "ai_simulation_sessions", "user_pair_locks", "recommendation_items",
            "recommendation_batches", "recommendation_passes", "user_blocks", "profiles", "users")) {
            jdbc.update("DELETE FROM " + table);
        }
        for (long id = 7; id <= 8; id++) {
            jdbc.update("""
                INSERT INTO users (id, birth_date, created_at, last_accessed_at, updated_at, name,
                    face_verification_status, gender, persona_onboarding_status, status)
                VALUES (?, '1995-01-01', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), '통합회원',
                    'NOT_VERIFIED', 'MALE', 'CONFIRMED', 'ACTIVE')
                """, id);
            jdbc.update(
                "INSERT INTO profiles (user_id, nickname, created_at, updated_at) VALUES (?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                id, "flow-" + id);
        }
        doAnswer(invocation -> generate(invocation.getArgument(0))).when(ai).run(any());
        mvc = MockMvcBuilders.standaloneSetup(new AiSimulationController(service, query))
            .setControllerAdvice(new GlobalExceptionHandler(), new AiSimulationExceptionHandler())
            .setCustomArgumentResolvers(new PrincipalResolver()).build();
    }

    @AfterEach
    void finishConsumers() {
        releaseAi.countDown();
        if (consumersStopped) {
            startConsumers();
        }
        awaitConsumed(routes());
        assertThat(registry.getAllListenerContainers().stream()
            .filter(container -> container.getListenerId().endsWith("-dlt")))
            .hasSize(5).allSatisfy(container -> assertThat(container.isRunning()).isFalse());
        verifyNoInteractions(discord);
    }

    @Test
    void API는_AI_완료_전에_202를_반환하고_다섯_Consumer가_결과와_부수_효과를_완성한다() throws Exception {
        long id = accepted();
        assertThat(aiStarted.await(10, TimeUnit.SECONDS)).isTrue();
        awaitRequested(id);
        assertThat(session(id).getStatus()).isEqualTo(AiSimulationSessionStatus.IN_PROGRESS);
        assertThat(count("ai_simulation_messages")).isZero();

        releaseAi.countDown();
        awaitCompleted(id);
        awaitConsumed(routes());

        for (long userId : List.of(7L, 8L)) {
            mvc.perform(get("/api/v1/ai-simulations/{id}", id).header("X-Test-User-Id", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transcript.length()").value(20))
                .andExpect(jsonPath("$.data.report.overall.score").value(78));
        }
        assertThat(query.get(7L, id)).isEqualTo(query.get(8L, id));
        assertThat(decoded(COMPLETED, id, AiSimulationCompletedEvent.class))
            .containsExactly(events.completed(id, session(id).getFinishedAt()));
        assertThat(decoded(REQUESTED, id, AiSimulationRequestedEvent.class))
            .containsExactly(events.requested(id, session(id).getCreatedAt()));
        verify(ai, times(1)).run(new SimulationRequest("7", "8", 10));
    }

    @Test
    void 동시_양방향_API_요청은_하나만_202이고_한_세션과_한_요청_이벤트를_생성한다() throws Exception {
        var gate = new CyclicBarrier(2);
        MvcResult first;
        MvcResult second;
        try (var callers = Executors.newFixedThreadPool(2)) {
            var a = callers.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return request(7L, 8L);
            });
            var b = callers.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return request(8L, 7L);
            });
            first = a.get(10, TimeUnit.SECONDS);
            second = b.get(10, TimeUnit.SECONDS);
        }
        assertThat(List.of(first.getResponse().getStatus(), second.getResponse().getStatus()))
            .containsExactlyInAnyOrder(202, 200);
        long id = simulationId(first);
        assertThat(simulationId(second)).isEqualTo(id);
        awaitRequested(id);
        releaseAi.countDown();
        awaitCompleted(id);
        awaitConsumed(routes());

        assertThat(count("ai_simulation_sessions")).isEqualTo(1);
        assertThat(count("ai_simulation_reports")).isEqualTo(1);
        assertThat(records(REQUESTED, id)).hasSize(1);
        verify(template, times(1)).send(eq(REQUESTED), eq(id), any());
        var session = session(id);
        verify(ai, times(1)).run(new SimulationRequest(session.getUserId().toString(),
            session.getTargetUserId().toString(), 10));
    }

    @ParameterizedTest
    @EnumSource(AiSimulationSessionStatus.class)
    void 기존_세션은_양쪽의_재요청에_200으로_반환하고_모든_Consumer의_업무를_새로_만들지_않는다(
        AiSimulationSessionStatus state) throws Exception {
        long id = persistence.begin(7L, 8L).getId();
        switch (state) {
            case COMPLETED -> persistence.completeGeneration(id, aiResponse());
            case FAILED -> persistence.fail(id);
            case CANCELED -> {
                jdbc.update(
                    "UPDATE ai_simulation_sessions SET status='CANCELED', finished_at=UTC_TIMESTAMP(6) WHERE id=?", id);
                jdbc.update("UPDATE ai_simulation_reports SET status='FAILED' WHERE session_id=?", id);
            }
            case IN_PROGRESS -> {}
        }
        var before = snapshot();

        for (long userId : List.of(7L, 8L)) {
            var response = request(userId, userId == 7 ? 8L : 7L);
            assertThat(response.getResponse().getStatus()).isEqualTo(200);
            assertThat(simulationId(response)).isEqualTo(id);
            assertThat(mapper.readTree(response.getResponse().getContentAsString()).get("data").get("status").asText())
                .isEqualTo(state.name());
        }
        awaitConsumed(routes());

        assertThat(snapshot()).isEqualTo(before);
        assertThat(records(REQUESTED, id)).isEmpty();
        verify(template, never()).send(eq(REQUESTED), anyLong(), any());
        verifyNoInteractions(ai);
    }

    @Test
    void 같은_요청과_완료_이벤트의_재전달은_AI를_다시_호출하거나_결과와_알림과_추천을_중복_생성하지_않는다()
        throws Exception {
        long id = accepted();
        awaitRequested(id);
        releaseAi.countDown();
        awaitCompleted(id);
        awaitConsumed(routes());
        var before = snapshot();
        var session = session(id);

        publisher.publishRequested(id, session.getCreatedAt());
        publisher.publishCompleted(id, session.getFinishedAt());
        awaitConsumed(routes());

        assertThat(snapshot()).isEqualTo(before);
        verify(ai, times(1)).run(any());
        assertThat(decoded(REQUESTED, id, AiSimulationRequestedEvent.class))
            .hasSize(2).allSatisfy(event -> assertThat(event).isEqualTo(events.requested(id, session.getCreatedAt())));
        assertThat(decoded(COMPLETED, id, AiSimulationCompletedEvent.class))
            .hasSize(3).allSatisfy(event -> assertThat(event).isEqualTo(events.completed(id, session.getFinishedAt())));
    }

    @Test
    void Consumer가_중지돼도_Kafka에_접수한_작업은_남고_재시작하면_전체_처리를_완료한다() throws Exception {
        var stopped = new CountDownLatch(5);
        consumersStopped = true;
        AiSimulationKafkaListeners.listenerIds()
            .forEach(id -> registry.getListenerContainer(id).stop(stopped::countDown));
        assertThat(stopped.await(15, TimeUnit.SECONDS)).isTrue();

        long id = accepted();
        assertThat(records(REQUESTED, id)).hasSize(1);
        assertThat(session(id).getStatus()).isEqualTo(AiSimulationSessionStatus.IN_PROGRESS);
        assertThat(request(8L, 7L).getResponse().getStatus()).isEqualTo(200);
        assertThat(count("notification_request_outbox")).isZero();
        assertThat(count("recommendation_items")).isZero();
        verifyNoInteractions(ai);

        startConsumers();
        awaitRequested(id);
        releaseAi.countDown();
        awaitCompleted(id);
        awaitConsumed(routes());

        assertThat(records(REQUESTED, id)).hasSize(1);
        verify(ai, times(1)).run(any());
    }

    @Test
    void Kafka_기록_후_발행_확인에_실패해도_503의_세션은_Consumer에서_완료할_수_있다() throws Exception {
        CompletableFuture<SendResult<Object, Object>> unconfirmed = mock(CompletableFuture.class);
        when(unconfirmed.get(properties.getPublishResultTimeoutMs(), TimeUnit.MILLISECONDS))
            .thenThrow(new TimeoutException());
        doAnswer(invocation -> {
            var actual = (CompletableFuture<?>) invocation.callRealMethod();
            actual.get(5, TimeUnit.SECONDS);
            return unconfirmed;
        }).when(template).send(eq(REQUESTED), anyLong(), any());

        var response = request(7L, 8L);
        assertThat(response.getResponse().getStatus()).isEqualTo(503);
        var error = mapper.readTree(response.getResponse().getContentAsString());
        assertThat(error.get("errorCode").asText()).isEqualTo("SIMULATION_REQUEST_PUBLISH_UNCONFIRMED");
        long id = error.get("simulationId").asLong();
        awaitRequested(id);
        assertThat(session(id).getStatus()).isEqualTo(AiSimulationSessionStatus.IN_PROGRESS);
        assertThat(session(id).getFinishedAt()).isNull();
        var reused = request(8L, 7L);
        assertThat(reused.getResponse().getStatus()).isEqualTo(200);
        assertThat(simulationId(reused)).isEqualTo(id);

        releaseAi.countDown();
        awaitCompleted(id);
        awaitConsumed(routes());

        assertThat(records(REQUESTED, id)).hasSize(1);
        verify(template, times(1)).send(eq(REQUESTED), eq(id), any());
        verify(ai, times(1)).run(any());
        assertThat(unconfirmed.isCancelled()).isFalse();
    }

    @Test
    void Kafka_전송_실패로_DB에만_남은_접수는_실패시키거나_재요청으로_자동_발행하지_않는다() throws Exception {
        doThrow(new KafkaException("test send failure")).when(template).send(eq(REQUESTED), anyLong(), any());
        var response = request(7L, 8L);
        assertThat(response.getResponse().getStatus()).isEqualTo(503);
        var error = mapper.readTree(response.getResponse().getContentAsString());
        assertThat(error.get("errorCode").asText()).isEqualTo("SIMULATION_REQUEST_PUBLISH_UNCONFIRMED");
        long id = error.get("simulationId").asLong();
        var before = snapshot();

        for (long userId : List.of(7L, 8L)) {
            var reused = request(userId, userId == 7 ? 8L : 7L);
            assertThat(reused.getResponse().getStatus()).isEqualTo(200);
            assertThat(simulationId(reused)).isEqualTo(id);
        }
        awaitConsumed(routes());

        assertThat(snapshot()).isEqualTo(before);
        assertThat(session(id).getStatus()).isEqualTo(AiSimulationSessionStatus.IN_PROGRESS);
        assertThat(session(id).getFinishedAt()).isNull();
        assertThat(count("ai_simulation_reports")).isEqualTo(1);
        assertThat(count("notification_request_outbox")).isZero();
        assertThat(count("recommendation_items")).isZero();
        assertThat(records(REQUESTED, id)).isEmpty();
        verify(template, times(1)).send(eq(REQUESTED), eq(id), any());
        verifyNoInteractions(ai);
    }

    @Test
    void AI_일시_오류는_재시도하면서_알림과_추천을_독립적으로_처리하고_최종_완료한다() throws Exception {
        var attempts = new AtomicInteger();
        doAnswer(invocation -> {
            if (attempts.incrementAndGet() < 3) {
                throw retryableFailure();
            }
            return generate(invocation.getArgument(0));
        }).when(ai).run(any());

        long id = accepted();
        awaitRequested(id);
        assertThat(aiStarted.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(session(id).getStatus()).isEqualTo(AiSimulationSessionStatus.IN_PROGRESS);
        releaseAi.countDown();
        awaitCompleted(id);
        awaitConsumed(routes());

        verify(ai, times(3)).run(new SimulationRequest("7", "8", 10));
        assertThat(decoded(REQUESTED + "-generation-retry", id, AiSimulationRequestedEvent.class))
            .hasSize(2)
            .allSatisfy(event -> assertThat(event).isEqualTo(events.requested(id, session(id).getCreatedAt())));
        assertThat(records(REQUESTED + "-generation-dlt", id)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"true, 4", "false, 1"})
    void 생성_최종_실패는_DLT와_DB에_반영하고_접수_알림과_추천은_유지한다(boolean retryable, int expectedCalls)
        throws Exception {
        doThrow(retryable
            ? retryableFailure()
            : new AiSimulationBusinessException(AiSimulationErrorCode.AI_SERVER_RESPONSE_INVALID))
            .when(ai).run(any());

        long id = accepted();
        awaitRequested(id);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(session(id).getStatus()).isEqualTo(AiSimulationSessionStatus.FAILED);
            assertThat(jdbc.queryForObject("SELECT status FROM ai_simulation_reports WHERE session_id=?",
                String.class, id)).isEqualTo("FAILED");
        });
        awaitConsumed(routes());

        assertThat(decoded(REQUESTED + "-generation-dlt", id, AiSimulationRequestedEvent.class))
            .containsExactly(events.requested(id, session(id).getCreatedAt()));
        assertThat(records(COMPLETED, id)).isEmpty();
        assertThat(count("ai_simulation_messages")).isZero();
        assertThat(outboxes.count()).isEqualTo(1);
        assertThat(count("recommendation_items")).isEqualTo(1);
        verify(ai, times(expectedCalls)).run(any());
        var before = snapshot();
        assertThat(request(8L, 7L).getResponse().getStatus()).isEqualTo(200);
        assertThat(snapshot()).isEqualTo(before);
        verify(template, times(1)).send(eq(REQUESTED), eq(id), any());
    }

    @Test
    void 요청_알림_커밋_후_오류는_중복_기록_없이_재시도하고_생성과_추천을_유지한다() throws Exception {
        var results = new CopyOnWriteArrayList<AiSimulationNotificationResult>();
        var first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            var result = (AiSimulationNotificationResult) invocation.callRealMethod();
            results.add(result);
            if (first.getAndSet(false)) {
                throw new QueryTimeoutException("test notification acknowledgement failure");
            }
            return result;
        }).when(notifications).requested(anyLong());

        long id = accepted();
        awaitRequested(id);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(results)
            .containsExactly(AiSimulationNotificationResult.RECORDED, AiSimulationNotificationResult.ALREADY_RECORDED));
        releaseAi.countDown();
        awaitCompleted(id);
        awaitConsumed(routes());

        assertThat(records(REQUESTED + "-request-notification-retry", id)).hasSize(1);
        assertThat(records(REQUESTED + "-request-notification-dlt", id)).isEmpty();
        verify(ai, times(1)).run(any());
    }

    @Test
    void 완료_발행_실패의_재시도는_AI를_재호출하지_않고_저장된_결과로_완료_알림을_기록한다() throws Exception {
        var first = new AtomicBoolean(true);
        var failedEvent = new AtomicReference<AiSimulationCompletedEvent>();
        doAnswer(invocation -> {
            if (first.getAndSet(false)) {
                failedEvent.set(invocation.getArgument(2));
                throw new org.apache.kafka.common.errors.TimeoutException("test completion send failure");
            }
            return invocation.callRealMethod();
        }).when(template).send(eq(COMPLETED), anyLong(), any());

        long id = accepted();
        awaitRequested(id);
        releaseAi.countDown();
        awaitCompleted(id);
        awaitConsumed(routes());

        verify(ai, times(1)).run(any());
        verify(template, times(2)).send(eq(COMPLETED), eq(id), any());
        assertThat(decoded(COMPLETED, id, AiSimulationCompletedEvent.class)).containsExactly(failedEvent.get());
        assertThat(failedEvent.get()).isEqualTo(events.completed(id, session(id).getFinishedAt()));
        assertThat(records(REQUESTED + "-generation-retry", id)).hasSize(1);
    }

    @Test
    void 상대방이_패스한_경우에도_생성은_완료하고_요청자_완료_알림만_기록한다() throws Exception {
        jdbc.update(
            "INSERT INTO recommendation_passes (passer_user_id, passed_user_id, created_at) VALUES (8, 7, UTC_TIMESTAMP(6))");
        long id = accepted();
        assertThat(aiStarted.await(10, TimeUnit.SECONDS)).isTrue();
        awaitConsumed(routes().stream().filter(route -> route.task().equals("request-notification")
            || route.task().equals("recommendation")).toList());
        assertThat(outboxes.count()).isZero();
        assertThat(count("recommendation_items")).isZero();

        releaseAi.countDown();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(session(id).getStatus()).isEqualTo(AiSimulationSessionStatus.COMPLETED);
            assertThat(outboxes.findAll()).singleElement().satisfies(outbox -> {
                var request = codec.decode(outbox.getPayload());
                assertThat(request.recipientUserId()).isEqualTo(7L);
                assertThat(request.type()).isEqualTo(NotificationType.AI_SIMULATION_REPORT_COMPLETED);
            });
        });
        awaitConsumed(routes());
        assertThat(count("ai_simulation_messages")).isEqualTo(20);
        assertThat(count("recommendation_items")).isZero();
        verify(ai, times(1)).run(any());
    }

    private long accepted() throws Exception {
        var response = request(7L, 8L);
        assertThat(response.getResponse().getStatus()).isEqualTo(202);
        return simulationId(response);
    }

    private MvcResult request(long userId, long targetId) throws Exception {
        return mvc.perform(post("/api/v1/ai-simulations").header("X-Test-User-Id", userId)
            .contentType(MediaType.APPLICATION_JSON).content("{\"targetUserId\":" + targetId + "}"))
            .andReturn();
    }

    private long simulationId(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).get("data").get("simulationId").asLong();
    }

    private SimulationResponse generate(SimulationRequest request) throws InterruptedException {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        aiStarted.countDown();
        assertThat(releaseAi.await(20, TimeUnit.SECONDS)).isTrue();
        var response = aiResponse();
        var me = response.me();
        var partner = response.partner();
        return new SimulationResponse(response.simulationId(),
            new PersonaBrief(me.personaId(), request.meUserId(), me.nickname(), me.version(), me.headline(),
                me.accuracy()),
            new PersonaBrief(partner.personaId(), request.partnerUserId(), partner.nickname(), partner.version(),
                partner.headline(), partner.accuracy()),
            response.turns(), response.transcript(), response.report(), response.createdAt());
    }

    private AiSimulationBusinessException retryableFailure() {
        return new AiSimulationBusinessException(AiSimulationErrorCode.AI_SERVER_UNAVAILABLE,
            new SocketTimeoutException());
    }

    private AiSimulationSession session(long id) {
        return sessions.findById(id).orElseThrow();
    }

    private void awaitRequested(long id) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var session = session(id);
            assertThat(outboxes.findAll()).singleElement().satisfies(outbox -> {
                var request = codec.decode(outbox.getPayload());
                assertThat(request.sourceEventId()).isEqualTo("AI_SIMULATION_REQUESTED:" + id);
                assertThat(request.recipientUserId()).isEqualTo(session.getTargetUserId());
                assertThat(request.requestedAt()).isEqualTo(session.getCreatedAt().toInstant(ZoneOffset.UTC));
            });
            assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM recommendation_items item JOIN recommendation_batches batch
                    ON batch.id=item.recommendation_batch_id
                WHERE batch.user_id=? AND item.candidate_user_id=? AND item.simulation_requested=true
                """, Integer.class, session.getTargetUserId(), session.getUserId())).isEqualTo(1);
        });
    }

    private void awaitCompleted(long id) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            var session = session(id);
            assertThat(session.getStatus()).isEqualTo(AiSimulationSessionStatus.COMPLETED);
            assertThat(count("ai_simulation_messages")).isEqualTo(20);
            assertThat(jdbc.queryForObject("SELECT status FROM ai_simulation_reports WHERE session_id=?",
                String.class, id)).isEqualTo("COMPLETED");
            var requests = outboxes.findAll().stream().map(row -> codec.decode(row.getPayload())).toList();
            assertThat(requests).hasSize(3);
            assertThat(
                requests.stream().filter(request -> request.type() == NotificationType.AI_SIMULATION_REPORT_COMPLETED))
                .hasSize(2).allSatisfy(request -> {
                    assertThat(request.requestedAt()).isEqualTo(session.getFinishedAt().toInstant(ZoneOffset.UTC));
                    assertThat(request.targetId()).isEqualTo(id);
                });
            assertThat(
                requests.stream().filter(request -> request.type() == NotificationType.AI_SIMULATION_REPORT_COMPLETED)
                    .map(NotificationCreateRequested::recipientUserId))
                .containsExactlyInAnyOrder(session.getUserId(), session.getTargetUserId());
            assertThat(count("recommendation_items")).isEqualTo(1);
        });
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        return List.of("ai_simulation_sessions", "ai_simulation_reports", "ai_simulation_messages",
            "notification_request_outbox", "recommendation_items", "recommendation_batches").stream()
            .collect(Collectors.toMap(table -> table,
                table -> jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id")));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void startConsumers() {
        AiSimulationKafkaListeners.listenerIds().forEach(id -> registry.getListenerContainer(id).start());
        consumersStopped = false;
    }

    private List<Route> routes() {
        return List.of(new Route("generation", properties.getGeneration().getGroupId(), REQUESTED),
            new Route("request-notification", properties.getRequestNotification().getGroupId(), REQUESTED),
            new Route("recommendation", properties.getRecommendation().getGroupId(), REQUESTED),
            new Route("requester-notification", properties.getRequesterNotification().getGroupId(), COMPLETED),
            new Route("target-notification", properties.getTargetNotification().getGroupId(), COMPLETED));
    }

    private void awaitConsumed(List<Route> routes) {
        try (var admin = Admin.create(Map.of("bootstrap.servers", broker.getBrokersAsString()))) {
            var partitions = routes.stream().flatMap(route -> List.of(route.topic(), route.retryTopic()).stream())
                .distinct()
                .flatMap(topic -> IntStream.range(0, 4).mapToObj(partition -> new TopicPartition(topic, partition)))
                .collect(Collectors.toMap(partition -> partition, partition -> OffsetSpec.latest()));
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                var ends = admin.listOffsets(partitions).all().get(5, TimeUnit.SECONDS);
                for (var route : routes) {
                    for (boolean retry : List.of(false, true)) {
                        var topic = retry ? route.retryTopic() : route.topic();
                        var group = route.groupId() + (retry ? "-" + route.task() + "-retry" : "");
                        var committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                            .get(5, TimeUnit.SECONDS);
                        for (int partition = 0; partition < 4; partition++) {
                            var key = new TopicPartition(topic, partition);
                            long end = ends.get(key).offset();
                            if (end > 0) {
                                assertThat(committed.get(key)).as("%s: %s", group, key).isNotNull();
                                assertThat(committed.get(key).offset()).as("%s: %s", group, key)
                                    .isGreaterThanOrEqualTo(end);
                            }
                        }
                    }
                }
                var latest = admin.listOffsets(partitions).all().get(5, TimeUnit.SECONDS);
                assertThat(latest.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().offset())))
                    .isEqualTo(ends.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().offset())));
            });
        }
    }

    private List<ConsumerRecord<Long, byte[]>> records(String topic, long id) {
        try (var consumer = new KafkaConsumer<Long, byte[]>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false), new LongDeserializer(), new ByteArrayDeserializer())) {
            var partitions = IntStream.range(0, 4).mapToObj(partition -> new TopicPartition(topic, partition)).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            var ends = consumer.endOffsets(partitions);
            var result = new ArrayList<ConsumerRecord<Long, byte[]>>();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(100)).forEach(record -> {
                    if (record.key() != null && record.key() == id) {
                        result.add(record);
                    }
                });
                if (partitions.stream().allMatch(partition -> consumer.position(partition) >= ends.get(partition))) {
                    return result;
                }
            }
            throw new AssertionError("Test records were not read: " + topic);
        }
    }

    private <T> List<T> decoded(String topic, long id, Class<T> type) {
        return records(topic, id).stream().map(record -> mapper.readValue(record.value(), type)).toList();
    }

    private record Route(String task, String groupId, String topic) {
        String retryTopic() {
            return topic + "-" + task + "-retry";
        }
    }

    private static final class PrincipalResolver implements HandlerMethodArgumentResolver {
        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.getParameterType() == ServiceAuthenticationPrincipal.class;
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
            NativeWebRequest request, WebDataBinderFactory factory) {
            return new ServiceAuthenticationPrincipal(Long.valueOf(request.getHeader("X-Test-User-Id")));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiSimulationKafkaProperties.class)
    static class Config {
        @Bean
        SimpleMeterRegistry meters() {
            return new SimpleMeterRegistry();
        }
    }
}
