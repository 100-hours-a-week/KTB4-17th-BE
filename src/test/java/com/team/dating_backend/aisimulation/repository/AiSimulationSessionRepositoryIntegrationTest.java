package com.team.dating_backend.aisimulation.repository;

import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.CREATED_AT;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.TARGET_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.aisimulation.entity.AiSimulationSession;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import jakarta.persistence.EntityManager;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(
    properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
    }
)
@Testcontainers
class AiSimulationSessionRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private AiSimulationSessionRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long committedSessionId;

    @AfterTransaction
    void cleanUpCommittedSession() {
        if (committedSessionId != null) {
            jdbc.update("DELETE FROM ai_simulation_sessions WHERE id = ?", committedSessionId);
        }
    }

    @ParameterizedTest
    @EnumSource(AiSimulationSessionStatus.class)
    void 모든_상태에서_양방향_조합을_조회하고_다른_조합은_제외한다(
        AiSimulationSessionStatus status) {
        AiSimulationSession session = repository.saveAndFlush(
            new AiSimulationSession(USER_ID, TARGET_ID, CREATED_AT));
        repository.saveAndFlush(new AiSimulationSession(USER_ID, 99L, CREATED_AT));
        repository.saveAndFlush(new AiSimulationSession(99L, TARGET_ID, CREATED_AT));
        jdbc.update("UPDATE ai_simulation_sessions SET status = ? WHERE id = ?",
            status.name(), session.getId());
        entityManager.clear();

        for (List<Long> pair : List.of(List.of(USER_ID, TARGET_ID), List.of(TARGET_ID, USER_ID))) {
            List<AiSimulationSession> result = repository.findForReuseByUserPair(
                pair.getFirst(), pair.getLast(), PageRequest.of(0, 10));

            assertThat(result).extracting(AiSimulationSession::getId)
                .containsExactly(session.getId());
            assertThat(result.getFirst().getStatus()).isEqualTo(status);
        }
    }

    @Test
    void 같은_조합의_기존_중복_세션을_ID_내림차순으로_조회하고_건수를_제한한다() {
        AiSimulationSession first = repository.saveAndFlush(
            new AiSimulationSession(USER_ID, TARGET_ID, CREATED_AT));
        AiSimulationSession second = repository.saveAndFlush(
            new AiSimulationSession(TARGET_ID, USER_ID, CREATED_AT));
        AiSimulationSession third = repository.saveAndFlush(
            new AiSimulationSession(USER_ID, TARGET_ID, CREATED_AT));
        entityManager.clear();

        assertThat(repository.findForReuseByUserPair(
            USER_ID, TARGET_ID, PageRequest.of(0, 10)))
            .extracting(AiSimulationSession::getId)
            .containsExactly(third.getId(), second.getId(), first.getId());
        assertThat(repository.findForReuseByUserPair(
            TARGET_ID, USER_ID, PageRequest.of(0, 1)))
            .extracting(AiSimulationSession::getId).containsExactly(third.getId());
        assertThat(repository.findForReuseByUserPair(
            USER_ID, 100L, PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    void 상대_ID가_없는_기존_세션은_읽을_수_있지만_조합_조회에는_포함하지_않는다() {
        jdbc.update("""
            INSERT INTO ai_simulation_sessions (user_id, status, created_at)
            VALUES (?, 'COMPLETED', ?)
            """, USER_ID, CREATED_AT);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM ai_simulation_sessions", Long.class);
        entityManager.clear();

        assertThat(repository.findById(id).orElseThrow().getTargetUserId()).isNull();
        assertThat(repository.findForReuseByUserPair(
            USER_ID, TARGET_ID, PageRequest.of(0, 10))).isEmpty();
        assertThat(repository.findForReuseByUserPair(
            TARGET_ID, USER_ID, PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    void 잠금_조회한_세션은_다른_DB_연결에서_수정할_수_없다() throws SQLException {
        TransactionTemplate creation = new TransactionTemplate(transactionManager);
        creation.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        AiSimulationSession session = creation.execute(transaction -> repository.saveAndFlush(
            new AiSimulationSession(USER_ID, TARGET_ID, CREATED_AT)));
        committedSessionId = session.getId();
        entityManager.clear();

        assertThat(repository.findByIdForUpdate(session.getId())).isPresent();

        try (var connection = DriverManager.getConnection(
            MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement();
                var update = connection.prepareStatement(
                    "UPDATE ai_simulation_sessions SET status = 'FAILED' WHERE id = ?")) {
                statement.execute("SET SESSION innodb_lock_wait_timeout = 1");
                update.setLong(1, session.getId());

                assertThatThrownBy(update::executeUpdate)
                    .isInstanceOfSatisfying(SQLException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(1205));
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void 없는_세션의_잠금_조회는_빈_결과를_반환한다() {
        assertThat(repository.findByIdForUpdate(Long.MAX_VALUE)).isEmpty();
    }
}
