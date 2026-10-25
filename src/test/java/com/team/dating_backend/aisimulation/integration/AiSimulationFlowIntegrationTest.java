package com.team.dating_backend.aisimulation.integration;

import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.aiResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.team.dating_backend.aisimulation.client.AiSimulationAiClient;
import com.team.dating_backend.aisimulation.config.AiSimulationAsyncConfig;
import com.team.dating_backend.aisimulation.config.AiSimulationProperties;
import com.team.dating_backend.aisimulation.controller.AiSimulationController;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.PersonaBrief;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationRequest;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationResponse;
import com.team.dating_backend.aisimulation.dto.request.AiSimulationCreateRequest;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import com.team.dating_backend.aisimulation.service.AiSimulationAdmissionService;
import com.team.dating_backend.aisimulation.service.AiSimulationPersistenceService;
import com.team.dating_backend.aisimulation.service.AiSimulationQueryService;
import com.team.dating_backend.aisimulation.service.AiSimulationRecoveryJob;
import com.team.dating_backend.aisimulation.service.AiSimulationResponseMapper;
import com.team.dating_backend.aisimulation.service.AiSimulationResponseValidator;
import com.team.dating_backend.aisimulation.service.AiSimulationService;
import com.team.dating_backend.aisimulation.service.AiSimulationTargetValidator;
import com.team.dating_backend.aisimulation.service.AiSimulationWorker;
import com.team.dating_backend.common.exception.GlobalExceptionHandler;
import com.team.dating_backend.common.lock.repository.UserPairLockRepository;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.repository.NotificationRepository;
import com.team.dating_backend.notification.support.NotificationRequestTestConfiguration;
import com.team.dating_backend.notification.support.NotificationRequestTestConfiguration.PublishedRequests;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
    properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
        "app.ai-simulation.async-pool-size=1", "app.ai-simulation.async-queue-capacity=1",
        "app.ai-simulation.async-shutdown-wait-seconds=1"}
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import(
    {AiSimulationAsyncConfig.class, AiSimulationService.class, AiSimulationAdmissionService.class,
        AiSimulationWorker.class, AiSimulationPersistenceService.class, AiSimulationQueryService.class,
        AiSimulationTargetValidator.class, AiSimulationResponseMapper.class, AiSimulationResponseValidator.class,
        AiSimulationRecoveryJob.class, UserPairLockRepository.class, NotificationRequestTestConfiguration.class,
        AiSimulationFlowIntegrationTest.Config.class}
)
class AiSimulationFlowIntegrationTest {
    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private AiSimulationService service;
    @Autowired
    private AiSimulationAdmissionService admission;
    @Autowired
    private AiSimulationPersistenceService persistence;
    @Autowired
    private AiSimulationQueryService query;
    @Autowired
    private AiSimulationRecoveryJob recovery;
    @Autowired
    private AiSimulationAiClient client;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private JdbcTemplate jdbc;
    private MockMvc mvc;
    private Long viewer = 7L;

    @Autowired
    private PublishedRequests publishedRequests;

    @BeforeEach
    void setUp() {
        publishedRequests.clear();
        reset(client);
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM ai_simulation_messages");
        jdbc.update("DELETE FROM ai_simulation_reports");
        jdbc.update("DELETE FROM ai_simulation_sessions");
        jdbc.update("DELETE FROM user_pair_locks");
        jdbc.update("DELETE FROM user_blocks");
        jdbc.update("DELETE FROM users");
        for (long id = 7; id <= 12; id++) {
            jdbc.update("""
                INSERT INTO users (id, birth_date, created_at, last_accessed_at, updated_at, name,
                    face_verification_status, gender, persona_onboarding_status, status)
                VALUES (?, '1995-01-01', NOW(6), NOW(6), NOW(6), '검증회원',
                    'NOT_VERIFIED', 'MALE', 'CONFIRMED', 'ACTIVE')
                """, id);
        }
        mvc = MockMvcBuilders.standaloneSetup(new AiSimulationController(service, query))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new PrincipalResolver()).build();
    }

    @ParameterizedTest
    @CsvSource({"7, 8, false", "8, 7, false", "7, 8, true", "8, 7, true"})
    void 차단_방향과_기존_결과_유무에_관계없이_접수와_상대별_조회를_막는다(
        Long blockerId, Long blockedId, boolean existingResult) throws Exception {
        Long existingId = null;
        if (existingResult) {
            existingId = persistence.begin(7L, 8L).getId();
            persistence.complete(existingId, aiResponse());
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
        verify(client, org.mockito.Mockito.never()).run(any());
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
        persistence.complete(decision.simulationId(), aiResponse());
        mvc.perform(get("/api/v1/ai-simulations/targets/7"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        mvc.perform(get("/api/v1/ai-simulations/targets/8"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ai-simulations/targets/999"))
            .andExpect(status().isNotFound());
        assertThat(count("ai_simulation_sessions")).isEqualTo(1);
        verify(client, org.mockito.Mockito.never()).run(any());
    }

    @Test
    void 동시에_반대_방향으로_접수해도_커밋된_세션과_리포트는_한개다() throws Exception {
        CyclicBarrier gate = new CyclicBarrier(2);
        try (var callers = Executors.newFixedThreadPool(2)) {
            var first = callers.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return admission.admit(7L, 8L);
            });
            var second = callers.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return admission.admit(8L, 7L);
            });
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertThat(a.simulationId()).isEqualTo(b.simulationId());
            assertThat(List.of(a.created(), b.created())).containsExactlyInAnyOrder(true, false);
            assertThat(count("ai_simulation_sessions")).isEqualTo(1);
            assertThat(count("ai_simulation_reports")).isEqualTo(1);
        }
    }

    @Test
    void 접수_API는_AI보다_먼저_반환되고_양쪽이_동일_결과를_조회한다() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        given(client.run(any())).willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            started.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("AI release timed out");
            }
            return responseFor(invocation.getArgument(0));
        });
        Long id;
        try {
            mvc.perform(post("/api/v1/ai-simulations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUserId\":8}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            id = query.getPair(7L, 8L).simulationId();
            assertThat(query.getState(7L, id).status().name()).isEqualTo("IN_PROGRESS");
            viewer = 8L;
            mvc.perform(post("/api/v1/ai-simulations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUserId\":7}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.simulationId").value(id));
            mvc.perform(get("/api/v1/ai-simulations/{id}/status", id)).andExpect(status().isOk());
            mvc.perform(get("/api/v1/ai-simulations/{id}", id)).andExpect(status().isConflict());
            viewer = 9L;
            mvc.perform(get("/api/v1/ai-simulations/{id}/status", id)).andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/ai-simulations/{id}", id)).andExpect(status().isNotFound());
            assertThat(count("ai_simulation_sessions")).isEqualTo(1);
        } finally {
            release.countDown();
        }
        waitFor(id, "COMPLETED");
        await().atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> assertThat(publishedRequests.snapshot()).singleElement()
                .satisfies(request -> assertThat(request.sourceEventId()).isEqualTo("AI_SIMULATION_COMPLETED:" + id)));
        assertThat(notificationRepository.count()).isZero();
        publishedRequests.persistPending();
        assertThat(notificationRepository.findAll()).singleElement().satisfies(notification -> {
            assertThat(notification.getUserId()).isEqualTo(7L);
            assertThat(notification.getType()).isEqualTo(NotificationType.AI_SIMULATION_REPORT_COMPLETED);
            assertThat(notification.getTitle()).isEqualTo("시뮬레이션 완료");
            assertThat(notification.getBody()).isEqualTo("두 사람의 대화 시뮬레이션과 분석 보고서가 준비됐어요.");
            assertThat(notification.getTargetType()).isEqualTo("AI_SIMULATION");
            assertThat(notification.getTargetId()).isEqualTo(id);
            assertThat(notification.getReadAt()).isNull();
            assertThat(notification.getDeletedAt()).isNull();
        });
        assertThat(notificationRepository.countVisibleUnreadByUserId(7L)).isEqualTo(1L);
        assertThat(notificationRepository.countVisibleUnreadByUserId(8L)).isZero();
        viewer = 8L;
        mvc.perform(get("/api/v1/ai-simulations/{id}", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.transcript.length()").value(20))
            .andExpect(jsonPath("$.data.me.userId").value(7));
        mvc.perform(get("/api/v1/ai-simulations/{id}/report", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.overall.score").value(78));
        mvc.perform(get("/api/v1/ai-simulations")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(
            post("/api/v1/ai-simulations").contentType(MediaType.APPLICATION_JSON).content("{\"targetUserId\":7}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.simulationId").value(id));
        assertThat(query.get(7L, id)).isEqualTo(query.get(8L, id));
        assertThatThrownBy(() -> persistence.complete(id, aiResponse()))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.SIMULATION_NOT_IN_PROGRESS));
        assertThat(notificationRepository.count()).isEqualTo(1L);
        assertThat(publishedRequests.snapshot()).hasSize(1);
        assertThat(count("ai_simulation_messages")).isEqualTo(20);
        verify(client, times(1)).run(any());
        jdbc.update("UPDATE ai_simulation_reports SET deleted_at=NOW(6) WHERE session_id=?", id);
        assertThat(query.list(7L)).isEmpty();
        assertThat(query.list(8L)).isEmpty();
    }

    @Test
    void 대기열_초과는_503과_실패_상태로_남고_기존_작업은_완료된다() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        given(client.run(any())).willAnswer(invocation -> {
            started.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("AI release timed out");
            }
            return responseFor(invocation.getArgument(0));
        });
        Long first;
        Long second;
        try {
            first = service.request(7L, new AiSimulationCreateRequest(8L)).simulationId();
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            second = service.request(7L, new AiSimulationCreateRequest(9L)).simulationId();
            mvc.perform(post("/api/v1/ai-simulations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUserId\":10}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("SIMULATION_CAPACITY_EXCEEDED"));
            Long rejected = query.getPair(7L, 10L).simulationId();
            assertThat(query.getState(7L, rejected).status().name()).isEqualTo("FAILED");
        } finally {
            release.countDown();
        }
        waitFor(first, "COMPLETED");
        waitFor(second, "COMPLETED");
        assertThat(count("ai_simulation_sessions")).isEqualTo(3);
        assertThat(count("ai_simulation_messages")).isEqualTo(40);
        verify(client, times(2)).run(any());
    }

    @Test
    void AI_실패는_조회에_반영되고_다시_요청해도_재실행하지_않는다() {
        given(client.run(any())).willThrow(new IllegalStateException("AI unavailable"));
        var handle = service.request(7L, new AiSimulationCreateRequest(8L));
        waitFor(handle.simulationId(), "FAILED");
        assertThat(service.request(8L, new AiSimulationCreateRequest(7L)).simulationId())
            .isEqualTo(handle.simulationId());
        verify(client, times(1)).run(any());
        assertThat(count("ai_simulation_messages")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM ai_simulation_reports WHERE session_id=?", String.class,
            handle.simulationId())).isEqualTo("FAILED");
        assertThat(notificationRepository.count()).isZero();
        assertThat(publishedRequests.snapshot()).isEmpty();
    }

    @Test
    void 잘못된_AI_응답은_메시지나_완료_결과를_저장하지_않는다() {
        var r = aiResponse();
        given(client.run(any())).willReturn(new SimulationResponse(r.simulationId(), r.me(), r.partner(), r.turns(),
            List.of(), r.report(), r.createdAt()));
        Long id = service.request(7L, new AiSimulationCreateRequest(8L)).simulationId();
        waitFor(id, "FAILED");
        assertThat(count("ai_simulation_messages")).isZero();
        assertThat(notificationRepository.count()).isZero();
        assertThat(publishedRequests.snapshot()).isEmpty();
    }

    @Test
    void 결과_저장_DB_오류는_롤백되고_실패만_별도_커밋된다() {
        jdbc.execute(
            "ALTER TABLE ai_simulation_messages ADD CONSTRAINT test_reject_message CHECK (JSON_EXTRACT(content, '$.index') < 5)");
        try {
            given(client.run(any())).willAnswer(invocation -> responseFor(invocation.getArgument(0)));
            Long id = service.request(7L, new AiSimulationCreateRequest(8L)).simulationId();
            waitFor(id, "FAILED");
            assertThat(count("ai_simulation_messages")).isZero();
            assertThat(jdbc.queryForObject("SELECT detail_result IS NULL FROM ai_simulation_reports WHERE session_id=?",
                Boolean.class, id)).isTrue();
            assertThat(
                jdbc.queryForObject("SELECT status FROM ai_simulation_reports WHERE session_id=?", String.class, id))
                .isEqualTo("FAILED");
        } finally {
            jdbc.execute("ALTER TABLE ai_simulation_messages DROP CHECK test_reject_message");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 알림_DB_오류에도_완료된_세션과_메시지와_리포트를_유지한다(boolean viaWorker) {
        jdbc.execute("""
            ALTER TABLE notifications ADD CONSTRAINT test_reject_simulation_notification
            CHECK (type <> 'AI_SIMULATION_REPORT_COMPLETED')
            """);
        try {
            Long id;
            if (viaWorker) {
                given(client.run(any())).willAnswer(invocation -> responseFor(invocation.getArgument(0)));
                id = service.request(7L, new AiSimulationCreateRequest(8L)).simulationId();
                waitFor(id, "COMPLETED");
            } else {
                id = persistence.begin(7L, 8L).getId();
                persistence.complete(id, aiResponse());
            }
            await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(publishedRequests.snapshot()).singleElement().satisfies(
                    request -> assertThat(request.sourceEventId()).isEqualTo("AI_SIMULATION_COMPLETED:" + id)));
            assertThatThrownBy(publishedRequests::persistPending)
                .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(query.getState(7L, id).status().name()).isEqualTo("COMPLETED");
            assertThat(query.getState(7L, id).finishedAt()).isNotNull();
            assertThat(count("ai_simulation_messages")).isEqualTo(20);
            assertThat(notificationRepository.count()).isZero();
            assertThat(jdbc.queryForMap("""
                SELECT status, completed_at IS NOT NULL AS completed, summary
                FROM ai_simulation_reports WHERE session_id = ?
                """, id))
                .containsEntry("status", "COMPLETED")
                .containsEntry("completed", 1L)
                .containsEntry("summary", "대화가 잘 이어져요.");
            assertThat(query.get(7L, id)).isEqualTo(query.get(8L, id));
        } finally {
            jdbc.execute("ALTER TABLE notifications DROP CHECK test_reject_simulation_notification");
        }
    }

    @Test
    void 복구는_기한을_넘긴_진행중_세션만_실패시키고_늦은_완료를_막는다() {
        var expired = admission.admit(7L, 8L);
        var fresh = admission.admit(7L, 9L);
        var completed = admission.admit(7L, 10L);
        persistence.complete(completed.simulationId(), responseFor(new SimulationRequest("7", "10", 10)));
        jdbc.update("UPDATE ai_simulation_sessions SET created_at=? WHERE id=?",
            LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1000), expired.simulationId());
        recovery.recoverExpired();
        assertThat(query.getState(7L, expired.simulationId()).status().name()).isEqualTo("FAILED");
        assertThat(query.getState(7L, fresh.simulationId()).status().name()).isEqualTo("IN_PROGRESS");
        assertThat(query.getState(7L, completed.simulationId()).status().name()).isEqualTo("COMPLETED");
        var finished = query.getState(7L, expired.simulationId()).finishedAt();
        recovery.recoverExpired();
        assertThat(query.getState(7L, expired.simulationId()).finishedAt()).isEqualTo(finished);
        assertThatThrownBy(() -> persistence.complete(expired.simulationId(), aiResponse()))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.SIMULATION_NOT_IN_PROGRESS));
    }

    @Test
    void 과거_중복은_최근_성공_결과를_재사용하고_기존_ID_조회도_유지한다() {
        var first = persistence.begin(7L, 8L);
        persistence.complete(first.getId(), aiResponse());
        jdbc.update("UPDATE ai_simulation_sessions SET finished_at=? WHERE id=?",
            LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5), first.getId());
        var second = persistence.begin(8L, 7L);
        persistence.complete(second.getId(), responseFor(new SimulationRequest("8", "7", 10)));
        var failed = persistence.begin(7L, 8L);
        persistence.fail(failed.getId());
        assertThat(admission.admit(7L, 8L).simulationId()).isEqualTo(second.getId());
        assertThat(admission.admit(8L, 7L).created()).isFalse();
        assertThat(query.getPair(7L, 8L).simulationId()).isEqualTo(second.getId());
        assertThat(query.get(7L, first.getId()).transcript()).hasSize(20);
        assertThat(query.get(7L, second.getId()).me().userId()).isEqualTo(8L);
    }

    private void waitFor(Long id, String expected) {
        await().atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(query.getState(7L, id).status().name()).isEqualTo(expected));
    }

    @Test
    void 양쪽의_동시_재시도는_실패를_보존하고_하나의_새_비동기_작업만_실행한다() throws Exception {
        Long failedId = persistence.begin(7L, 8L).getId();
        persistence.fail(failedId);
        var failedState = query.getState(7L, failedId);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        given(client.run(any())).willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            started.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("AI release timed out");
            }
            return responseFor(invocation.getArgument(0));
        });
        CyclicBarrier gate = new CyclicBarrier(2);
        Long attemptId;
        try {
            try (var callers = Executors.newFixedThreadPool(2)) {
                var first = callers.submit(() -> {
                    gate.await(10, TimeUnit.SECONDS);
                    return service.attempt(7L, failedId);
                });
                var second = callers.submit(() -> {
                    gate.await(10, TimeUnit.SECONDS);
                    return service.attempt(8L, failedId);
                });
                var a = first.get(10, TimeUnit.SECONDS);
                var b = second.get(10, TimeUnit.SECONDS);
                assertThat(a).isEqualTo(b);
                attemptId = a.simulationId();
            }
            assertThat(attemptId).isGreaterThan(failedId);
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(count("ai_simulation_sessions")).isEqualTo(2);
            assertThat(count("ai_simulation_reports")).isEqualTo(2);
            viewer = 8L;
            mvc.perform(post("/api/v1/ai-simulations/{id}/attempts", failedId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.simulationId").value(attemptId));
            mvc.perform(get("/api/v1/ai-simulations/{id}/status", attemptId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        } finally {
            release.countDown();
        }
        waitFor(attemptId, "COMPLETED");
        mvc.perform(post("/api/v1/ai-simulations/{id}/attempts", failedId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.simulationId").value(attemptId))
            .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        assertThat(query.getState(7L, failedId)).isEqualTo(failedState);
        assertThat(query.get(7L, attemptId)).isEqualTo(query.get(8L, attemptId));
        assertThat(count("ai_simulation_sessions")).isEqualTo(2);
        assertThat(count("ai_simulation_messages")).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_simulation_reports WHERE session_id=?", String.class,
            failedId)).isEqualTo("FAILED");
        verify(client, times(1)).run(new SimulationRequest("7", "8", 10));
    }

    @Test
    void 새_시도가_다시_실패해도_이전_ID는_재실행하지_않고_새_ID로만_다음_시도를_만든다() {
        Long failedId = persistence.begin(7L, 8L).getId();
        persistence.fail(failedId);
        var next = admission.admitAttempt(8L, failedId);
        persistence.fail(next.simulationId());

        assertThat(admission.admitAttempt(7L, failedId).created()).isFalse();
        assertThat(admission.admitAttempt(8L, failedId).simulationId()).isEqualTo(next.simulationId());
        assertThat(count("ai_simulation_sessions")).isEqualTo(2);
        var third = admission.admitAttempt(7L, next.simulationId());
        assertThat(third.created()).isTrue();
        assertThat(third.simulationId()).isGreaterThan(next.simulationId());
        assertThat(query.getState(7L, failedId).status().name()).isEqualTo("FAILED");
        assertThat(query.getState(7L, next.simulationId()).status().name()).isEqualTo("FAILED");
        assertThat(query.getState(8L, third.simulationId()).status().name()).isEqualTo("IN_PROGRESS");
        assertThat(count("ai_simulation_reports")).isEqualTo(3);
    }

    @Test
    void 재시도의_진행과_실패는_기존_성공_결과를_유지하고_새_성공만_공유_결과를_교체한다() {
        Long completedId = persistence.begin(7L, 8L).getId();
        persistence.complete(completedId, aiResponse());
        Long failedId = persistence.begin(8L, 7L).getId();
        persistence.fail(failedId);
        var next = admission.admitAttempt(7L, failedId);
        assertThat(query.getPair(7L, 8L).simulationId()).isEqualTo(completedId);
        persistence.fail(next.simulationId());
        assertThat(query.getPair(8L, 7L).simulationId()).isEqualTo(completedId);
        var last = admission.admitAttempt(8L, next.simulationId());
        persistence.complete(last.simulationId(), responseFor(new SimulationRequest("8", "7", 10)));

        assertThat(query.getPair(7L, 8L).simulationId()).isEqualTo(last.simulationId());
        assertThat(query.getPair(8L, 7L).simulationId()).isEqualTo(last.simulationId());
        assertThat(admission.admitAttempt(7L, failedId).simulationId()).isEqualTo(last.simulationId());
        assertThat(query.get(7L, last.simulationId()).me().userId()).isEqualTo(8L);
        assertThat(count("ai_simulation_sessions")).isEqualTo(4);
    }

    @Test
    void 재시도는_다른_사용자나_차단_관계에서_새_작업을_만들지_않는다() throws Exception {
        Long failedId = persistence.begin(7L, 8L).getId();
        persistence.fail(failedId);
        viewer = 9L;
        mvc.perform(post("/api/v1/ai-simulations/{id}/attempts", failedId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("SIMULATION_NOT_FOUND"));
        jdbc.update("INSERT INTO user_blocks (blocker_user_id, blocked_user_id, blocked_at) VALUES (8, 7, NOW(6))");
        for (long userId : List.of(7L, 8L)) {
            viewer = userId;
            mvc.perform(post("/api/v1/ai-simulations/{id}/attempts", failedId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TARGET_USER_NOT_FOUND"));
        }
        assertThat(count("ai_simulation_sessions")).isEqualTo(1);
        verify(client, org.mockito.Mockito.never()).run(any());
    }

    @Test
    void 시도_대기열_초과는_새_실패_ID를_반환하고_그_ID로_후속_재시도가_가능하다() throws Exception {
        Long failedId = persistence.begin(7L, 10L).getId();
        persistence.fail(failedId);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        given(client.run(any())).willAnswer(invocation -> {
            started.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("AI release timed out");
            }
            return responseFor(invocation.getArgument(0));
        });
        Long first;
        Long second;
        Long rejected;
        try {
            first = service.request(7L, new AiSimulationCreateRequest(8L)).simulationId();
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            second = service.request(7L, new AiSimulationCreateRequest(9L)).simulationId();
            var response = mvc.perform(post("/api/v1/ai-simulations/{id}/attempts", failedId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("SIMULATION_CAPACITY_EXCEEDED"))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andReturn().getResponse();
            rejected = new ObjectMapper().readTree(response.getContentAsString()).get("simulationId").asLong();
            assertThat(rejected).isGreaterThan(failedId);
            assertThat(query.getState(7L, rejected).status().name()).isEqualTo("FAILED");
            mvc.perform(post("/api/v1/ai-simulations/{id}/attempts", failedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.simulationId").value(rejected))
                .andExpect(jsonPath("$.data.status").value("FAILED"));
        } finally {
            release.countDown();
        }
        waitFor(first, "COMPLETED");
        waitFor(second, "COMPLETED");
        Long accepted = service.attempt(10L, rejected).simulationId();
        waitFor(accepted, "COMPLETED");
        assertThat(query.getState(7L, failedId).status().name()).isEqualTo("FAILED");
        assertThat(query.getState(7L, rejected).status().name()).isEqualTo("FAILED");
        assertThat(count("ai_simulation_sessions")).isEqualTo(5);
        verify(client, times(3)).run(any());
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
    @EnableConfigurationProperties(AiSimulationProperties.class)
    static class Config {
        @Bean
        AiSimulationAiClient aiClient() {
            return org.mockito.Mockito.mock(AiSimulationAiClient.class);
        }
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
