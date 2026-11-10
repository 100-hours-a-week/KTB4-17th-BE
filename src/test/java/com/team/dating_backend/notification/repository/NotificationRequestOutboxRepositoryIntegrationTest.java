package com.team.dating_backend.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.enums.NotificationOutboxStatus;
import com.team.dating_backend.notification.enums.NotificationType;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class NotificationRequestOutboxRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private NotificationRequestOutboxRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        now = jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class);
    }

    @AfterEach
    void cleanUp() {
        repository.deleteAllInBatch();
    }

    @Test
    void 재시도_시각과_ID_순서로_한_건만_선택하고_원본과_처리_정보를_변경하지_않는다() {
        save(now.minusMinutes(1));
        Long expected = save(now.minusMinutes(2));
        save(now.minusMinutes(2));
        jdbc.update("""
            UPDATE notification_request_outbox SET failure_count = 2, last_failure_type = 'PUBLISH_RESULT_TIMEOUT'
            WHERE id = ?
            """, expected);
        var before = jdbc.queryForList("SELECT * FROM notification_request_outbox ORDER BY id");

        var selected = selectNext().orElseThrow();

        assertThat(selected.getId()).isEqualTo(expected);
        assertThat(selected.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(selected.getProcessingToken()).isNull();
        assertThat(selected.getProcessingUntil()).isNull();
        assertThat(jdbc.queryForList("SELECT * FROM notification_request_outbox ORDER BY id")).isEqualTo(before);
    }

    @Test
    void 미래_재시도_요청과_점유_기한이_유효한_요청은_제외한다() {
        save(now.plusHours(1));
        Long claimed = save(now.minusMinutes(2));
        jdbc.update("""
            UPDATE notification_request_outbox SET processing_token = ?, processing_until = ? WHERE id = ?
            """, UUID.randomUUID().toString(), now.plusHours(1), claimed);

        assertThat(selectNext()).isEmpty();
    }

    @Test
    void 점유가_만료된_요청은_기존_토큰을_변경하지_않고_선택한다() {
        Long id = save(now.minusMinutes(2));
        String token = UUID.randomUUID().toString();
        LocalDateTime expiredAt = now.minusMinutes(1);
        jdbc.update("""
            UPDATE notification_request_outbox SET processing_token = ?, processing_until = ? WHERE id = ?
            """, token, expiredAt, id);

        var selected = selectNext().orElseThrow();

        assertThat(selected.getId()).isEqualTo(id);
        assertThat(selected.getProcessingToken()).isEqualTo(token);
        assertThat(selected.getProcessingUntil()).isEqualTo(expiredAt);
        assertThat(selected.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationOutboxStatus.class, names = {"PUBLISHED", "FAILED", "COMPLETED"})
    void 종료된_요청은_선택하지_않는다(NotificationOutboxStatus status) {
        Long id = save(now.minusMinutes(1));
        jdbc.update("UPDATE notification_request_outbox SET status = ? WHERE id = ?", status.name(), id);

        assertThat(selectNext()).isEmpty();
    }

    @Test
    void 요청이_없으면_빈_결과를_반환한다() {
        assertThat(selectNext()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 잠긴_후보를_기다리지_않고_다음_행이나_빈_결과를_반환한다(boolean hasUnlockedCandidate) throws Exception {
        Long first = save(now.minusMinutes(2));
        Long second = save(now.minusMinutes(1));
        Long[] lockedIds = hasUnlockedCandidate ? new Long[]{first} : new Long[]{first, second};

        try (var holder = lockRows(lockedIds); var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(this::selectNext);
            try {
                assertThat(result.get(5, TimeUnit.SECONDS).map(NotificationRequestOutbox::getId))
                    .isEqualTo(hasUnlockedCandidate ? Optional.of(second) : Optional.empty());
            } finally {
                holder.rollback();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 조회_잠금은_트랜잭션_안에서_유지되고_커밋이나_롤백으로_해제된다(boolean rollback) {
        Long first = save(now.minusMinutes(2));
        Long second = save(now.minusMinutes(1));

        try (var executor = Executors.newSingleThreadExecutor()) {
            transaction.executeWithoutResult(status -> {
                assertThat(repository.findNextAvailableForUpdateSkipLocked().orElseThrow().getId()).isEqualTo(first);
                var result = executor.submit(this::selectNext);
                try {
                    assertThat(result.get(5, TimeUnit.SECONDS).orElseThrow().getId()).isEqualTo(second);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
                if (rollback) {
                    status.setRollbackOnly();
                }
            });
        }

        assertThat(selectNext().orElseThrow().getId()).isEqualTo(first);
    }

    @Test
    void 트랜잭션_없이_잠금_조회를_호출하면_거부한다() {
        assertThatThrownBy(repository::findNextAvailableForUpdateSkipLocked)
            .isInstanceOf(IllegalTransactionStateException.class);
    }

    private Long save(LocalDateTime nextAttemptAt) {
        return repository.saveAndFlush(new NotificationRequestOutbox("event:" + UUID.randomUUID(), 7L,
            NotificationType.LIKE, "{\"snapshot\":\"원본\"}", nextAttemptAt)).getId();
    }

    private Optional<NotificationRequestOutbox> selectNext() {
        return transaction.execute(status -> repository.findNextAvailableForUpdateSkipLocked());
    }

    private Connection lockRows(Long... ids) throws Exception {
        var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        try {
            connection.setAutoCommit(false);
            for (Long id : ids) {
                try (var statement = connection.prepareStatement(
                    "SELECT id FROM notification_request_outbox WHERE id = ? FOR UPDATE")) {
                    statement.setLong(1, id);
                    try (var result = statement.executeQuery()) {
                        assertThat(result.next()).isTrue();
                    }
                }
            }
            return connection;
        } catch (Exception | Error failure) {
            try {
                connection.close();
            } catch (Exception closingFailure) {
                failure.addSuppressed(closingFailure);
            }
            throw failure;
        }
    }
}
