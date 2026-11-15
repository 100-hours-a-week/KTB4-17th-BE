package com.team.dating_backend.aisimulation.integration;

import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.aiResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.aisimulation.config.AiSimulationKafkaProperties;
import com.team.dating_backend.aisimulation.controller.AiSimulationController;
import com.team.dating_backend.aisimulation.controller.AiSimulationExceptionHandler;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.PersonaBrief;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationRequest;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationResponse;
import com.team.dating_backend.aisimulation.dto.event.AiSimulationRequestedEvent;
import com.team.dating_backend.aisimulation.dto.request.AiSimulationCreateRequest;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import com.team.dating_backend.aisimulation.enums.AiSimulationNotificationRecipient;
import com.team.dating_backend.aisimulation.service.AiSimulationAdmissionService;
import com.team.dating_backend.aisimulation.service.AiSimulationEventFactory;
import com.team.dating_backend.aisimulation.service.AiSimulationEventPublisher;
import com.team.dating_backend.aisimulation.service.AiSimulationNotificationService;
import com.team.dating_backend.aisimulation.service.AiSimulationPersistenceService;
import com.team.dating_backend.aisimulation.service.AiSimulationQueryService;
import com.team.dating_backend.aisimulation.service.AiSimulationResponseMapper;
import com.team.dating_backend.aisimulation.service.AiSimulationResponseValidator;
import com.team.dating_backend.aisimulation.service.AiSimulationService;
import com.team.dating_backend.aisimulation.service.AiSimulationTargetValidator;
import com.team.dating_backend.common.exception.GlobalExceptionHandler;
import com.team.dating_backend.common.kafka.config.KafkaProducerConfiguration;
import com.team.dating_backend.common.lock.repository.UserPairLockRepository;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.support.NotificationRequestTestConfiguration.StoredRequests;
import com.team.dating_backend.notification.support.NotificationRequestTestConfiguration;
import com.team.dating_backend.profile.cache.ProfileCacheExpirationPolicy;
import com.team.dating_backend.profile.cache.UserProfileCache;
import com.team.dating_backend.profile.config.ProfileCacheProperties;
import com.team.dating_backend.profile.controller.UserProfileController;
import com.team.dating_backend.profile.service.PopularProfileRankingService;
import com.team.dating_backend.profile.service.ProfileAvailabilityValidator;
import com.team.dating_backend.profile.service.ProfileImageGetService;
import com.team.dating_backend.profile.service.UserProfileGetService;
import com.team.dating_backend.profile.service.UserProfileSourceLoader;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
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
import tools.jackson.databind.ObjectMapper;

@DataJpaTest(
    showSql = false, properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.kafka.admin.auto-create=false", "app.profile-cache.enabled=false",
        "app.ai-simulation.kafka.requested-topic=admission-test-requested",
        "app.ai-simulation.kafka.completed-topic=admission-test-completed",
        "app.ai-simulation.kafka.replication-factor=1", "app.ai-simulation.kafka.min-in-sync-replicas=1"}
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@DirtiesContext
@Timeout(45)
@EmbeddedKafka(
    partitions = 4, topics = "admission-test-requested",
    brokerProperties = "auto.create.topics.enable=false"
)
@ImportAutoConfiguration({KafkaAutoConfiguration.class, JacksonAutoConfiguration.class})
@Import(
    {AiSimulationService.class, AiSimulationAdmissionService.class,
        AiSimulationPersistenceService.class, AiSimulationQueryService.class,
        AiSimulationTargetValidator.class, AiSimulationResponseMapper.class, AiSimulationResponseValidator.class,
        AiSimulationNotificationService.class,
        AiSimulationEventPublisher.class, AiSimulationEventFactory.class, KafkaProducerConfiguration.class,
        UserProfileGetService.class,
        UserProfileSourceLoader.class, ProfileAvailabilityValidator.class,
        UserPairLockRepository.class, NotificationRequestTestConfiguration.class,
        AiSimulationFlowIntegrationTest.Config.class}
)
class AiSimulationFlowIntegrationTest {
    private static final String REQUESTED = "admission-test-requested";

    @MockitoSpyBean(name = "kafkaTemplate")
    private KafkaTemplate<?, ?> faultTemplate;
    @Autowired
    private KafkaTemplate<Object, Object> template;
    @Autowired
    private EmbeddedKafkaBroker broker;
    @Autowired
    private AiSimulationKafkaProperties kafkaProperties;
    @Autowired
    private ObjectMapper mapper;

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private AiSimulationService service;
    @Autowired
    private AiSimulationAdmissionService admission;
    @Autowired
    private AiSimulationNotificationService simulationNotifications;
    @Autowired
    private UserProfileGetService profileService;
    @Autowired
    private AiSimulationPersistenceService persistence;
    @Autowired
    private AiSimulationQueryService query;
    @Autowired
    private JdbcTemplate jdbc;
    private MockMvc mvc;
    private Long viewer = 7L;

    @Autowired
    private StoredRequests storedRequests;

    @BeforeEach
    void setUp() {
        storedRequests.clear();
        kafkaProperties.setPublishResultTimeoutMs(5_000);
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM ai_simulation_messages");
        jdbc.update("DELETE FROM ai_simulation_reports");
        jdbc.update("DELETE FROM ai_simulation_sessions");
        jdbc.update("DELETE FROM user_pair_locks");
        jdbc.update("DELETE FROM user_blocks");
        jdbc.update("DELETE FROM recommendation_items");
        jdbc.update("DELETE FROM recommendation_batches");
        jdbc.update("DELETE FROM recommendation_passes");
        jdbc.update("DELETE FROM profiles");
        jdbc.update("DELETE FROM users");
        for (long id = 7; id <= 12; id++) {
            jdbc.update("""
                INSERT INTO users (id, birth_date, created_at, last_accessed_at, updated_at, name,
                    face_verification_status, gender, persona_onboarding_status, status)
                VALUES (?, '1995-01-01', NOW(6), NOW(6), NOW(6), '검증회원',
                    'NOT_VERIFIED', 'MALE', 'CONFIRMED', 'ACTIVE')
                """, id);
        }
        mvc = MockMvcBuilders
            .standaloneSetup(new AiSimulationController(service, query), new UserProfileController(profileService))
            .setControllerAdvice(new GlobalExceptionHandler(), new AiSimulationExceptionHandler())
            .setCustomArgumentResolvers(new PrincipalResolver()).build();
    }

    @Test
    void 완료_후_양쪽_프로필_API는_패스와_차단에_관계없이_조회한다() throws Exception {
        jdbc.update(
            "insert into profiles (user_id, nickname, created_at, updated_at) values (7, '요청자', now(6), now(6)), (8, '상대', now(6), now(6))");
        jdbc.update(
            "insert into recommendation_passes (passer_user_id, passed_user_id, created_at) values (8, 7, now(6))");
        Long id = persistence.begin(7L, 8L).getId();
        persistence.completeGeneration(id, aiResponse());
        viewer = 7L;
        mvc.perform(get("/api/v1/users/8/profile")).andExpect(status().isOk());
        viewer = 8L;
        mvc.perform(get("/api/v1/users/7/profile")).andExpect(status().isOk());
        jdbc.update("insert into user_blocks (blocker_user_id, blocked_user_id, blocked_at) values (7, 8, now(6))");
        mvc.perform(get("/api/v1/users/7/profile")).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"blocked", "left", "target_withdrawn", "requester_withdrawn"})
    void 결과_저장_후_관계가_변해도_A의_수신_상태만으로_A의_알림을_판단한다(String situation) {
        Long id = persistence.begin(7L, 8L).getId();
        persistence.completeGeneration(id, aiResponse());
        try {
            switch (situation) {
                case "blocked" -> jdbc.update(
                    "INSERT INTO user_blocks (blocker_user_id, blocked_user_id, blocked_at) VALUES (8, 7, NOW(6))");
                case "left" -> {
                    jdbc.update(
                        "INSERT INTO matches (id, sender_id, receiver_id, status, matched_at) VALUES (990001, 7, 8, 'ACTIVE', NOW(6))");
                    jdbc.update(
                        "INSERT INTO chat_rooms (id, match_id, status, created_at) VALUES (990001, 990001, 'ACTIVE', NOW(6))");
                    jdbc.update(
                        "INSERT INTO chat_participants (chat_room_id, user_id, status, is_chat_notification) VALUES (990001, 7, 'ACTIVE', true), (990001, 8, 'LEFT', true)");
                }
                case "target_withdrawn" -> jdbc.update("UPDATE users SET status = 'WITHDRAWN' WHERE id = 8");
                case "requester_withdrawn" -> jdbc.update("UPDATE users SET status = 'WITHDRAWN' WHERE id = 7");
                default -> throw new IllegalArgumentException(situation);
            }

            simulationNotifications.completed(id, AiSimulationNotificationRecipient.REQUESTER);
            simulationNotifications.completed(id, AiSimulationNotificationRecipient.TARGET);

            if (situation.equals("requester_withdrawn")) {
                assertThat(storedRequests.snapshot()).isEmpty();
            } else {
                assertThat(storedRequests.snapshot()).singleElement().satisfies(request -> {
                    assertThat(request.recipientUserId()).isEqualTo(7L);
                    assertThat(request.type()).isEqualTo(NotificationType.AI_SIMULATION_REPORT_COMPLETED);
                });
            }
            assertThat(jdbc.queryForObject("SELECT status FROM ai_simulation_sessions WHERE id = ?", String.class, id))
                .isEqualTo("COMPLETED");
        } finally {
            if (situation.equals("left")) {
                jdbc.update("DELETE FROM chat_participants WHERE chat_room_id = 990001");
                jdbc.update("DELETE FROM chat_rooms WHERE id = 990001");
                jdbc.update("DELETE FROM matches WHERE id = 990001");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"7, 8, false", "8, 7, false", "7, 8, true", "8, 7, true"})
    void 차단_방향과_기존_결과_유무에_관계없이_접수와_상대별_조회를_막는다(
        Long blockerId, Long blockedId, boolean existingResult) throws Exception {
        Long existingId = null;
        if (existingResult) {
            existingId = persistence.begin(7L, 8L).getId();
            persistence.completeGeneration(existingId, aiResponse());
        }
        jdbc.update("INSERT INTO user_blocks (blocker_user_id, blocked_user_id, blocked_at) VALUES (?, ?, NOW(6))",
            blockerId, blockedId);

        for (long userId : List.of(7L, 8L)) {
            viewer = userId;
            long targetId = userId == 7L ? 8L : 7L;
            mvc.perform(post("/api/v1/ai-simulations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUserId\":" + targetId + "}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TARGET_USER_NOT_FOUND"));
            mvc.perform(get("/api/v1/ai-simulations/targets/{targetId}", targetId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TARGET_USER_NOT_FOUND"));
        }

        assertThat(count("ai_simulation_sessions")).isEqualTo(existingResult ? 1 : 0);
        verify(template, org.mockito.Mockito.never()).send(any(String.class), any(Long.class), any());
        jdbc.update("UPDATE user_blocks SET unblocked_at = NOW(6)");
        assertThat(query.getPair(7L, 8L).simulationId()).isEqualTo(existingId);
        if (existingResult) {
            assertThat(service.request(7L, new AiSimulationCreateRequest(8L)).simulationId()).isEqualTo(existingId);
        }
    }

    @Test
    void 상대별_조회는_생성하지_않고_진행중과_완료_결과를_동일하게_찾는다() throws Exception {
        mvc.perform(get("/api/v1/ai-simulations/targets/8"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.targetUserId").value(8))
            .andExpect(jsonPath("$.data.simulationId").isEmpty());
        assertThat(count("ai_simulation_sessions")).isZero();
        var decision = admission.admit(7L, 8L);
        viewer = 8L;
        mvc.perform(get("/api/v1/ai-simulations/targets/7"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.simulationId").value(decision.simulationId()))
            .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        persistence.completeGeneration(decision.simulationId(), aiResponse());
        mvc.perform(get("/api/v1/ai-simulations/targets/7"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        mvc.perform(get("/api/v1/ai-simulations/targets/8"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ai-simulations/targets/999"))
            .andExpect(status().isNotFound());
        assertThat(count("ai_simulation_sessions")).isEqualTo(1);
        verify(template, org.mockito.Mockito.never()).send(any(String.class), any(Long.class), any());
    }

    @Test
    void 동시에_반대_방향으로_접수해도_커밋된_세션과_리포트는_한개다() throws Exception {
        CyclicBarrier gate = new CyclicBarrier(2);
        try (var callers = Executors.newFixedThreadPool(2)) {
            var first = callers.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return service.request(7L, new AiSimulationCreateRequest(8L));
            });
            var second = callers.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return service.request(8L, new AiSimulationCreateRequest(7L));
            });
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertThat(a.simulationId()).isEqualTo(b.simulationId());
            assertThat(List.of(a.created(), b.created())).containsExactlyInAnyOrder(true, false);
            var savedAt = jdbc.queryForObject("SELECT created_at FROM ai_simulation_sessions WHERE id=?",
                LocalDateTime.class, a.simulationId());
            assertThat(a.createdAt()).isEqualTo(savedAt);
            assertThat(b.createdAt()).isEqualTo(savedAt);
            assertThat(count("ai_simulation_sessions")).isEqualTo(1);
            assertThat(count("ai_simulation_reports")).isEqualTo(1);
            verify(template, times(1)).send(eq(REQUESTED), eq(a.simulationId()), any());
        }
    }

    @Test
    void 생성_시각이_오래된_진행중_세션도_재사용하고_정상_완료할_수_있다() {
        var admitted = admission.admit(7L, 8L);
        var createdAt = LocalDateTime.now(ZoneOffset.UTC).minusDays(1).withNano(0);
        jdbc.update("UPDATE ai_simulation_sessions SET created_at=? WHERE id=?",
            createdAt, admitted.simulationId());

        var reused = admission.admit(8L, 7L);

        assertThat(reused.simulationId()).isEqualTo(admitted.simulationId());
        assertThat(reused.created()).isFalse();
        assertThat(reused.createdAt()).isEqualTo(createdAt);
        assertThat(reused.status().name()).isEqualTo("IN_PROGRESS");
        assertThat(query.getState(7L, admitted.simulationId()).finishedAt()).isNull();

        var completed = persistence.completeGeneration(admitted.simulationId(),
            aiResponse());

        assertThat(completed.status().name()).isEqualTo("COMPLETED");
        assertThat(count("ai_simulation_sessions")).isEqualTo(1);
        assertThat(count("ai_simulation_messages")).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_simulation_reports WHERE session_id=?",
            String.class, admitted.simulationId())).isEqualTo("COMPLETED");
    }

    @Test
    void 과거_중복은_최근_성공_결과를_재사용하고_기존_ID_조회도_유지한다() {
        var first = persistence.begin(7L, 8L);
        persistence.completeGeneration(first.getId(), aiResponse());
        jdbc.update("UPDATE ai_simulation_sessions SET finished_at=? WHERE id=?",
            LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5), first.getId());
        var second = persistence.begin(8L, 7L);
        persistence.completeGeneration(second.getId(), responseFor(new SimulationRequest("8", "7", 10)));
        var failed = persistence.begin(7L, 8L);
        persistence.fail(failed.getId());
        assertThat(admission.admit(7L, 8L).simulationId()).isEqualTo(second.getId());
        assertThat(admission.admit(8L, 7L).created()).isFalse();
        assertThat(query.getPair(7L, 8L).simulationId()).isEqualTo(second.getId());
        assertThat(query.get(7L, first.getId()).transcript()).hasSize(20);
        assertThat(query.get(7L, second.getId()).me().userId()).isEqualTo(8L);
    }

    @Test
    void 신규_접수는_저장된_정보로_Kafka에_발행하고_업무_효과를_직접_실행하지_않는다() throws Exception {
        var result = mvc.perform(post("/api/v1/ai-simulations").contentType(MediaType.APPLICATION_JSON)
            .content("{\"targetUserId\":8}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.message").value("ai_simulation_accepted"))
            .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.created").doesNotExist())
            .andExpect(jsonPath("$.data.createdAt").doesNotExist())
            .andReturn();
        long id = mapper.readTree(result.getResponse().getContentAsString()).get("data").get("simulationId").asLong();

        var record = requestedRecord(id);
        var event = mapper.readValue(record.value(), AiSimulationRequestedEvent.class);
        var savedAt = jdbc.queryForObject("SELECT created_at FROM ai_simulation_sessions WHERE id=?",
            LocalDateTime.class, id);

        assertThat(record.key()).isEqualTo(id);
        assertThat(event.simulationId()).isEqualTo(id);
        assertThat(event.eventId()).isEqualTo("ai-simulation:" + id + ":requested");
        assertThat(event.occurredAt()).isEqualTo(savedAt.toInstant(ZoneOffset.UTC));
        assertThat(query.getState(7L, id).status()).isEqualTo(AiSimulationSessionStatus.IN_PROGRESS);
        assertThat(count("ai_simulation_reports")).isEqualTo(1);
        assertThat(count("ai_simulation_messages")).isZero();
        assertThat(count("notification_request_outbox")).isZero();
        assertThat(count("recommendation_items")).isZero();
    }

    @ParameterizedTest
    @EnumSource(AiSimulationSessionStatus.class)
    void 기존_세션은_상태와_무관하게_200으로_반환하고_재발행하지_않는다(AiSimulationSessionStatus sessionStatus)
        throws Exception {
        long id = persistence.begin(7L, 8L).getId();
        switch (sessionStatus) {
            case COMPLETED -> persistence.completeGeneration(id, aiResponse());
            case FAILED -> persistence.fail(id);
            case CANCELED -> jdbc.update(
                "UPDATE ai_simulation_sessions SET status='CANCELED', finished_at=UTC_TIMESTAMP(6) WHERE id=?", id);
            case IN_PROGRESS -> {}
        }
        viewer = 8L;

        mvc.perform(post("/api/v1/ai-simulations").contentType(MediaType.APPLICATION_JSON)
            .content("{\"targetUserId\":7}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("ai_simulation_existing"))
            .andExpect(jsonPath("$.data.simulationId").value(id))
            .andExpect(jsonPath("$.data.status").value(sessionStatus.name()));

        verify(template, org.mockito.Mockito.never()).send(any(String.class), any(Long.class), any());
        assertThat(count("ai_simulation_sessions")).isEqualTo(1);
        assertThat(count("notification_request_outbox")).isZero();
        assertThat(count("recommendation_items")).isZero();
    }

    @Test
    void DB_커밋_이후_발행하며_Kafka_성공_확인_전에는_202를_반환하지_않는다() throws Exception {
        var sent = new CountDownLatch(1);
        var releaseAck = new CompletableFuture<Void>();
        doAnswer(invocation -> {
            var event = invocation.getArgument(2, AiSimulationRequestedEvent.class);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT status FROM ai_simulation_sessions WHERE id=?",
                String.class, event.simulationId())).isEqualTo("IN_PROGRESS");
            assertThat(count("ai_simulation_reports")).isEqualTo(1);
            var actual = (CompletableFuture<?>) invocation.callRealMethod();
            sent.countDown();
            return actual.thenCompose(metadata -> releaseAck.thenApply(ignored -> metadata));
        }).when(template).send(eq(REQUESTED), any(Long.class), any());

        try (var callers = Executors.newSingleThreadExecutor()) {
            var response = callers.submit(() -> mvc.perform(post("/api/v1/ai-simulations")
                .contentType(MediaType.APPLICATION_JSON).content("{\"targetUserId\":8}"))
                .andExpect(status().isAccepted()));
            try {
                assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> response.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
                releaseAck.complete(null);
                response.get(5, TimeUnit.SECONDS);
            } finally {
                releaseAck.complete(null);
            }
        }
    }

    @Test
    void Kafka에_기록돼도_확인_시간이_초과되면_503과_세션_ID를_반환하고_접수를_보존한다() throws Exception {
        kafkaProperties.setPublishResultTimeoutMs(20);
        var unconfirmed = new CompletableFuture<SendResult<Object, Object>>();
        doAnswer(invocation -> {
            var actual = (CompletableFuture<?>) invocation.callRealMethod();
            actual.get(5, TimeUnit.SECONDS);
            return unconfirmed;
        }).when(template).send(eq(REQUESTED), any(Long.class), any());

        var result = mvc.perform(post("/api/v1/ai-simulations").contentType(MediaType.APPLICATION_JSON)
            .content("{\"targetUserId\":8}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.errorCode").value("SIMULATION_REQUEST_PUBLISH_UNCONFIRMED"))
            .andExpect(jsonPath("$.status").doesNotExist())
            .andReturn();
        long id = mapper.readTree(result.getResponse().getContentAsString()).get("simulationId").asLong();

        assertThat(requestedRecord(id).key()).isEqualTo(id);
        assertThat(unconfirmed.isCancelled()).isFalse();
        assertThat(query.getState(7L, id).status()).isEqualTo(AiSimulationSessionStatus.IN_PROGRESS);
        assertThat(query.getState(7L, id).finishedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM ai_simulation_reports WHERE session_id=?",
            String.class, id)).isEqualTo("GENERATING");

        mvc.perform(post("/api/v1/ai-simulations").contentType(MediaType.APPLICATION_JSON)
            .content("{\"targetUserId\":8}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.simulationId").value(id));
        verify(template, times(1)).send(eq(REQUESTED), eq(id), any());
        assertThat(count("ai_simulation_sessions")).isEqualTo(1);
        assertThat(count("notification_request_outbox")).isZero();
        assertThat(count("recommendation_items")).isZero();
    }

    @Test
    void send_호출_실패도_세션을_실패시키지_않고_발행_미확인_오류로_반환한다() throws Exception {
        doThrow(new org.apache.kafka.common.KafkaException("test send failure"))
            .when(template).send(eq(REQUESTED), any(Long.class), any());

        mvc.perform(post("/api/v1/ai-simulations").contentType(MediaType.APPLICATION_JSON)
            .content("{\"targetUserId\":8}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.errorCode").value("SIMULATION_REQUEST_PUBLISH_UNCONFIRMED"))
            .andExpect(jsonPath("$.simulationId").isNumber());

        assertThat(jdbc.queryForList("SELECT status FROM ai_simulation_sessions", String.class))
            .containsExactly("IN_PROGRESS");
        assertThat(jdbc.queryForList("SELECT status FROM ai_simulation_reports", String.class))
            .containsExactly("GENERATING");
    }

    @Test
    void 접수_DB_저장_실패는_롤백하고_Kafka에_발행하지_않는다() throws Exception {
        jdbc.execute(
            "ALTER TABLE ai_simulation_reports ADD CONSTRAINT reject_test_admission CHECK (status <> 'GENERATING')");
        try {
            mvc.perform(post("/api/v1/ai-simulations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUserId\":8}"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("{\"errorCode\":\"INTERNAL_SERVER_ERROR\"}"));

            assertThat(count("ai_simulation_sessions")).isZero();
            assertThat(count("ai_simulation_reports")).isZero();
            verify(template, org.mockito.Mockito.never()).send(any(String.class), any(Long.class), any());
        } finally {
            jdbc.execute("ALTER TABLE ai_simulation_reports DROP CHECK reject_test_admission");
        }
    }

    private ConsumerRecord<Long, byte[]> requestedRecord(long id) {
        try (var consumer = new KafkaConsumer<Long, byte[]>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, LongDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false))) {
            var partitions = IntStream.range(0, 4).mapToObj(partition -> new TopicPartition(REQUESTED, partition))
                .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            var records = new java.util.ArrayList<ConsumerRecord<Long, byte[]>>();
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                consumer.poll(Duration.ofMillis(100)).forEach(record -> {
                    if (record.key() != null && record.key() == id) {
                        records.add(record);
                    }
                });
                return !records.isEmpty();
            });
            assertThat(records).hasSize(1);
            return records.getFirst();
        }
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static SimulationResponse responseFor(SimulationRequest request) {
        var r = aiResponse();
        var a = r.me();
        var b = r.partner();
        return new SimulationResponse(r.simulationId(),
            new PersonaBrief(a.personaId(), request.meUserId(), a.nickname(), a.version(), a.headline(), a.accuracy()),
            new PersonaBrief(b.personaId(), request.partnerUserId(), b.nickname(), b.version(), b.headline(),
                b.accuracy()),
            r.turns(), r.transcript(), r.report(), r.createdAt());
    }

    private class PrincipalResolver implements HandlerMethodArgumentResolver {
        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.getParameterType() == ServiceAuthenticationPrincipal.class;
        }
        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
            NativeWebRequest request, WebDataBinderFactory factory) {
            return new ServiceAuthenticationPrincipal(viewer);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({AiSimulationKafkaProperties.class, ProfileCacheProperties.class})
    static class Config {
        @Bean
        UserProfileCache userProfileCache() {
            return org.mockito.Mockito.mock(UserProfileCache.class);
        }
        @Bean
        ProfileCacheExpirationPolicy profileCacheExpirationPolicy() {
            return org.mockito.Mockito.mock(ProfileCacheExpirationPolicy.class);
        }
        @Bean
        ProfileImageGetService profileImageGetService() {
            return org.mockito.Mockito.mock(ProfileImageGetService.class);
        }
        @Bean
        PopularProfileRankingService popularProfileRankingService() {
            return org.mockito.Mockito.mock(PopularProfileRankingService.class);
        }
    }
}
