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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
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
        AiSimulationRecoveryJob.class, UserPairLockRepository.class, AiSimulationFlowIntegrationTest.Config.class}
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
    private JdbcTemplate jdbc;
    private MockMvc mvc;
    private Long viewer = 7L;

    @BeforeEach
    void setUp() {
        reset(client);
        jdbc.update("DELETE FROM ai_simulation_messages");
        jdbc.update("DELETE FROM ai_simulation_reports");
        jdbc.update("DELETE FROM ai_simulation_sessions");
        jdbc.update("DELETE FROM user_pair_locks");
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
                .content("{\"targetMemberId\":8}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            id = query.getPair(7L, 8L).simulationId();
            assertThat(query.getState(7L, id).status().name()).isEqualTo("IN_PROGRESS");
            viewer = 8L;
            mvc.perform(post("/api/v1/ai-simulations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetMemberId\":7}"))
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
        viewer = 8L;
        mvc.perform(get("/api/v1/ai-simulations/{id}", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.transcript.length()").value(20))
            .andExpect(jsonPath("$.data.me.userId").value(7));
        mvc.perform(get("/api/v1/ai-simulations/{id}/report", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.overall.score").value(78));
        mvc.perform(get("/api/v1/ai-simulations")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(
            post("/api/v1/ai-simulations").contentType(MediaType.APPLICATION_JSON).content("{\"targetMemberId\":7}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.simulationId").value(id));
        assertThat(query.get(7L, id)).isEqualTo(query.get(8L, id));
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
                .content("{\"targetMemberId\":10}"))
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
    }

    @Test
    void 잘못된_AI_응답은_메시지나_완료_결과를_저장하지_않는다() {
        var r = aiResponse();
        given(client.run(any())).willReturn(new SimulationResponse(r.simulationId(), r.me(), r.partner(), r.turns(),
            List.of(), r.report(), r.createdAt()));
        Long id = service.request(7L, new AiSimulationCreateRequest(8L)).simulationId();
        waitFor(id, "FAILED");
        assertThat(count("ai_simulation_messages")).isZero();
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
            .isInstanceOf(IllegalStateException.class);
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
