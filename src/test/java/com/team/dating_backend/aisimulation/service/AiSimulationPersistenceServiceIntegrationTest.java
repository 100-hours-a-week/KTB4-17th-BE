package com.team.dating_backend.aisimulation.service;

import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.TARGET_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.USER_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.aiResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import java.time.LocalDateTime;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

@DataJpaTest(
    showSql = false, properties = {
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"
    }
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    {AiSimulationPersistenceService.class, AiSimulationPersistenceServiceIntegrationTest.MappingConfiguration.class}
)
@Testcontainers
@Timeout(30)
class AiSimulationPersistenceServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private AiSimulationPersistenceService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM ai_simulation_messages");
        jdbc.update("DELETE FROM ai_simulation_reports");
        jdbc.update("DELETE FROM ai_simulation_sessions");
    }

    @Test
    void 커밋된_완료_결과를_재조회하고_재처리해도_최초_완료_시각과_결과를_유지한다() {
        Long id = service.begin(USER_ID, TARGET_ID).getId();

        var first = service.completeGeneration(id, aiResponse());
        var replayed = service.completeGeneration(id, null);

        assertThat(service.loadGenerationState(id)).isEqualTo(first).isEqualTo(replayed);
        assertThat(first.status()).isEqualTo(AiSimulationSessionStatus.COMPLETED);
        assertThat(first.finishedAt()).isNotNull();
        assertThat(first.finishedAt().getNano() % 1_000).isZero();
        assertThat(jdbc.queryForObject("SELECT completed_at FROM ai_simulation_reports WHERE session_id=?",
            LocalDateTime.class, id)).isEqualTo(first.finishedAt());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_simulation_messages WHERE session_id=?",
            Integer.class, id)).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_simulation_reports WHERE session_id=?",
            String.class, id)).isEqualTo("COMPLETED");
    }

    @Test
    void 동시에_완료를_저장해도_한_결과만_커밋하고_같은_완료_시각을_반환한다() throws Exception {
        Long id = service.begin(USER_ID, TARGET_ID).getId();
        var gate = new CyclicBarrier(2);
        try (var callers = Executors.newFixedThreadPool(2)) {
            var first = callers.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return service.completeGeneration(id, aiResponse());
            });
            var second = callers.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return service.completeGeneration(id, aiResponse());
            });

            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_simulation_messages WHERE session_id=?",
            Integer.class, id)).isEqualTo(20);
        assertThat(service.loadGenerationState(id).status()).isEqualTo(AiSimulationSessionStatus.COMPLETED);
    }

    @ParameterizedTest
    @EnumSource(value = AiSimulationSessionStatus.class, names = {"FAILED", "CANCELED"})
    void 조회_이후_다른_트랜잭션에서_종료한_세션에는_결과를_저장하지_않는다(AiSimulationSessionStatus status) {
        Long id = service.begin(USER_ID, TARGET_ID).getId();
        assertThat(service.loadGenerationState(id).status()).isEqualTo(AiSimulationSessionStatus.IN_PROGRESS);
        jdbc.update("UPDATE ai_simulation_sessions SET status=?, finished_at=UTC_TIMESTAMP(6) WHERE id=?",
            status.name(), id);

        var result = service.completeGeneration(id, aiResponse());

        assertThat(result.status()).isEqualTo(status);
        assertThat(service.loadGenerationState(id)).isEqualTo(result);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_simulation_messages WHERE session_id=?",
            Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM ai_simulation_reports WHERE session_id=?",
            String.class, id)).isEqualTo("GENERATING");
    }

    @Test
    void 리포트_갱신_실패는_메시지와_완료_상태를_함께_롤백하고_다음_저장을_허용한다() {
        Long id = service.begin(USER_ID, TARGET_ID).getId();
        jdbc.execute(
            "ALTER TABLE ai_simulation_reports ADD CONSTRAINT reject_test_completion CHECK (status <> 'COMPLETED')");
        try {
            assertThatThrownBy(() -> service.completeGeneration(id, aiResponse()))
                .isInstanceOf(DataAccessException.class);

            var state = service.loadGenerationState(id);
            assertThat(state.status()).isEqualTo(AiSimulationSessionStatus.IN_PROGRESS);
            assertThat(state.finishedAt()).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_simulation_messages WHERE session_id=?",
                Integer.class, id)).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM ai_simulation_reports WHERE session_id=?",
                String.class, id)).isEqualTo("GENERATING");
            assertThat(jdbc.queryForObject("SELECT detail_result IS NULL FROM ai_simulation_reports WHERE session_id=?",
                Boolean.class, id)).isTrue();
        } finally {
            jdbc.execute("ALTER TABLE ai_simulation_reports DROP CHECK reject_test_completion");
        }
        assertThat(service.completeGeneration(id, aiResponse()).status())
            .isEqualTo(AiSimulationSessionStatus.COMPLETED);
    }

    @Test
    void 상태_조회_반환_후에는_다른_트랜잭션에서_같은_세션을_잠글_수_있다() throws Exception {
        Long id = service.begin(USER_ID, TARGET_ID).getId();
        service.loadGenerationState(id);
        try (var callers = Executors.newSingleThreadExecutor()) {
            var updated = callers.submit(() -> new TransactionTemplate(transactionManager).execute(tx -> {
                jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");
                return jdbc.update("UPDATE ai_simulation_sessions SET status='CANCELED' WHERE id=?", id);
            }));
            assertThat(updated.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(service.loadGenerationState(id).status()).isEqualTo(AiSimulationSessionStatus.CANCELED);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MappingConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
