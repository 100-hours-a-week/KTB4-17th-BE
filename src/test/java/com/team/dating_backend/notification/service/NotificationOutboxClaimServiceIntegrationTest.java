package com.team.dating_backend.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.config.NotificationOutboxClaimProperties;
import com.team.dating_backend.notification.config.NotificationOutboxRetryProperties;
import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.enums.NotificationOutboxStatus;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.outbox.NotificationOutboxRetryPolicy;
import com.team.dating_backend.notification.repository.NotificationRequestOutboxRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    {NotificationOutboxClaimService.class, NotificationOutboxFailureService.class,
        NotificationOutboxRetryPolicy.class}
)
@Testcontainers
class NotificationOutboxClaimServiceIntegrationTest {

    private static final NotificationFailureCode FAILURE = NotificationFailureCode.PUBLISH_RESULT_TIMEOUT;

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private NotificationOutboxClaimService service;
    @Autowired
    private NotificationOutboxFailureService failures;
    @Autowired
    private NotificationRequestOutboxRepository repository;
    @Autowired
    private NotificationOutboxClaimProperties properties;
    @Autowired
    private NotificationOutboxRetryProperties retry;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;
    private Long id;

    @BeforeEach
    void setUp() {
        properties.setDurationMs(60_000L);
        retry.setMaxFailures(5);
        retry.setInitialDelayMs(1000);
        retry.setMaxDelayMs(60_000);
        transaction = new TransactionTemplate(transactionManager);
        id = repository.saveAndFlush(new NotificationRequestOutbox("event:501", 7L, NotificationType.LIKE,
            "{\"snapshot\":\"원본\"}", dbNow().minusMinutes(1))).getId();
    }

    @AfterEach
    void cleanUp() {
        repository.deleteAllInBatch();
    }

    @Test
    void 담당_확보는_DB_시각에_설정_기간을_더하고_원본과_이력을_보존한다() {
        jdbc.update("""
            UPDATE notification_request_outbox
            SET failure_count = 2, last_failure_type = 'PUBLISH_CALL_FAILED' WHERE id = ?
            """, id);
        var before = withoutProcessing(row());
        properties.setDurationMs(90_123L);
        var started = dbNow();

        var claimed = claim();

        var ended = dbNow();
        assertThat(UUID.fromString(claimed.getProcessingToken()).toString()).isEqualTo(claimed.getProcessingToken());
        assertThat(claimed.getProcessingUntil()).isBetween(started.plusNanos(90_123_000_000L),
            ended.plusNanos(90_123_000_000L));
        assertThat(withoutProcessing(row())).isEqualTo(before);
        assertThat(repository.findById(id).orElseThrow().getProcessingToken()).isEqualTo(claimed.getProcessingToken());
    }

    @Test
    void 유효한_담당이_있으면_재확보하지_않고_만료되면_새_토큰으로_확보한다() {
        String first = claim().getProcessingToken();
        var assigned = row();

        assertThat(service.tryClaim(id)).isEmpty();
        assertThat(row()).isEqualTo(assigned);
        expire();
        var expired = withoutProcessing(row());

        String second = claim().getProcessingToken();

        assertThat(second).isNotEqualTo(first);
        assertThat(withoutProcessing(row())).isEqualTo(expired);
    }

    @Test
    void 재시도_시각_전에는_미담당과_만료된_담당_모두_확보하지_않는다() {
        jdbc.update("""
            UPDATE notification_request_outbox SET next_attempt_at = TIMESTAMPADD(HOUR, 1, UTC_TIMESTAMP(6))
            WHERE id = ?
            """, id);
        var before = row();
        assertThat(service.tryClaim(id)).isEmpty();
        assertThat(row()).isEqualTo(before);

        jdbc.update("""
            UPDATE notification_request_outbox
            SET processing_token = ?, processing_until = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) WHERE id = ?
            """, UUID.randomUUID().toString(), id);
        before = row();
        assertThat(service.tryClaim(id)).isEmpty();
        assertThat(row()).isEqualTo(before);

        makeDue();
        assertThat(service.tryClaim(id)).isPresent();
    }

    @ParameterizedTest
    @EnumSource(value = NotificationOutboxStatus.class, names = {"PUBLISHED", "FAILED"})
    void 종료된_요청은_확보하거나_결과와_담당을_변경하지_않는다(NotificationOutboxStatus status) {
        jdbc.update("UPDATE notification_request_outbox SET status = ? WHERE id = ?", status.name(), id);
        var before = row();
        String token = UUID.randomUUID().toString();

        assertThat(service.tryClaim(id)).isEmpty();
        assertThat(service.markPublished(id, token)).isFalse();
        assertThat(failures.recordFailure(id, token, FAILURE)).isEmpty();
        assertThat(service.release(id, token)).isFalse();
        assertThat(row()).isEqualTo(before);
    }

    @Test
    void 같은_행을_동시에_확보하면_한_실행만_성공한다() throws Exception {
        var results = concurrently(() -> service.tryClaim(id), () -> service.tryClaim(id));

        var winners = results.stream().flatMap(Optional::stream).toList();
        assertThat(winners).hasSize(1);
        assertThat(repository.findById(id).orElseThrow().getProcessingToken())
            .isEqualTo(winners.getFirst().getProcessingToken());
    }

    @Test
    void 만료된_행의_동시_재확보도_새_토큰_하나만_성공한다() throws Exception {
        String old = claim().getProcessingToken();
        expire();

        var results = concurrently(() -> service.tryClaim(id), () -> service.tryClaim(id));

        var winners = results.stream().flatMap(Optional::stream).toList();
        assertThat(winners).hasSize(1);
        assertThat(winners.getFirst().getProcessingToken()).isNotEqualTo(old);
    }

    @Test
    void 현재_담당만_성공을_기록하고_담당_정보를_함께_정리한다() {
        jdbc.update("""
            UPDATE notification_request_outbox
            SET failure_count = 2, last_failure_type = 'PUBLISH_CALL_FAILED' WHERE id = ?
            """, id);
        String token = claim().getProcessingToken();
        var before = new LinkedHashMap<>(row());
        var started = dbNow();

        assertThat(service.markPublished(id, token)).isTrue();

        var stored = repository.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(NotificationOutboxStatus.PUBLISHED);
        assertThat(stored.getPublishedAt()).isBetween(started, dbNow());
        assertThat(stored.getProcessingToken()).isNull();
        assertThat(stored.getProcessingUntil()).isNull();
        for (String field : List.of("status", "published_at", "processing_token", "processing_until")) {
            before.remove(field);
        }
        assertThat(row()).containsAllEntriesOf(before);
        var published = row();
        assertThat(service.markPublished(id, token)).isFalse();
        assertThat(failures.recordFailure(id, token, FAILURE)).isEmpty();
        assertThat(row()).isEqualTo(published);
    }

    @Test
    void 재확보_후_이전_실행의_성공과_실패와_해제는_새_담당을_변경하지_못한다() {
        String old = claim().getProcessingToken();
        expire();
        String current = claim().getProcessingToken();
        var before = row();

        assertThat(service.markPublished(id, old)).isFalse();
        assertThat(failures.recordFailure(id, old, FAILURE)).isEmpty();
        assertThat(service.release(id, old)).isFalse();
        assertThat(row()).isEqualTo(before);
        assertThat(service.markPublished(id, current)).isTrue();
    }

    @Test
    void 토큰이_같아도_만료된_담당의_결과와_해제를_거절한다() {
        String token = claim().getProcessingToken();
        expire();
        var before = row();

        assertThat(service.markPublished(id, token)).isFalse();
        assertThat(failures.recordFailure(id, token, FAILURE)).isEmpty();
        assertThat(service.release(id, token)).isFalse();
        assertThat(row()).isEqualTo(before);
    }

    @Test
    void 다른_토큰과_대소문자가_다른_토큰은_현재_담당을_변경하지_못한다() {
        String token = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
        jdbc.update("""
            UPDATE notification_request_outbox SET processing_token = ?,
                processing_until = TIMESTAMPADD(MINUTE, 1, UTC_TIMESTAMP(6)) WHERE id = ?
            """, token, id);
        var before = row();
        for (String other : List.of(UUID.randomUUID().toString(), token.toUpperCase(java.util.Locale.ROOT))) {
            assertThat(service.markPublished(id, other)).isFalse();
            assertThat(failures.recordFailure(id, other, FAILURE)).isEmpty();
            assertThat(service.release(id, other)).isFalse();
        }
        assertThat(row()).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(
        value = NotificationFailureCode.class, names = {"OUTBOX_RESTORE_FAILED",
            "OUTBOX_PUBLISH_REQUIRES_ATTENTION", "PUBLISH_CALL_FAILED", "PUBLISH_RESULT_FAILED",
            "PUBLISH_RESULT_TIMEOUT"}
    )
    void 실패는_기존_한도와_지수_대기시간을_유지하고_담당을_정리한다(NotificationFailureCode code) {
        var original = withoutProcessing(row());
        for (int count = 1; count <= 5; count++) {
            makeDue();
            String token = claim().getProcessingToken();
            var started = dbNow();

            var result = failures.recordFailure(id, token, code).orElseThrow();

            var ended = dbNow();
            var stored = repository.findById(id).orElseThrow();
            assertThat(result.failureCount()).isEqualTo(count);
            assertThat(result.exhausted()).isEqualTo(count == 5);
            assertThat(stored.getFailureCount()).isEqualTo(count);
            assertThat(stored.getLastFailureType()).isEqualTo(code.name());
            assertThat(stored.getStatus()).isEqualTo(count == 5
                ? NotificationOutboxStatus.FAILED
                : NotificationOutboxStatus.PENDING);
            long seconds = count == 5 ? 0 : 1L << (count - 1);
            assertThat(stored.getNextAttemptAt()).isBetween(started.plusSeconds(seconds), ended.plusSeconds(seconds));
            assertThat(stored.getProcessingToken()).isNull();
            assertThat(stored.getProcessingUntil()).isNull();
            var recorded = row();
            assertThat(failures.recordFailure(id, token, code)).isEmpty();
            assertThat(row()).isEqualTo(recorded);
            if (count < 5) {
                assertThat(service.tryClaim(id)).isEmpty();
            }
        }
        for (String field : List.of("status", "failure_count", "last_failure_type", "next_attempt_at")) {
            original.remove(field);
        }
        assertThat(row()).containsAllEntriesOf(original);
        assertThat(service.tryClaim(id)).isEmpty();
    }

    @Test
    void 같은_담당의_실패를_동시에_기록해도_횟수는_한_번만_증가한다() throws Exception {
        String token = claim().getProcessingToken();

        var results = concurrently(() -> failures.recordFailure(id, token, FAILURE),
            () -> failures.recordFailure(id, token, FAILURE));

        assertThat(results.stream().flatMap(Optional::stream).toList()).hasSize(1);
        assertThat(repository.findById(id).orElseThrow().getFailureCount()).isEqualTo(1);
    }

    @Test
    void 성공과_실패를_동시에_기록하면_하나의_결과만_반영한다() throws Exception {
        String token = claim().getProcessingToken();

        var results = concurrently(() -> service.markPublished(id, token),
            () -> failures.recordFailure(id, token, FAILURE).isPresent());

        assertThat(results).containsExactlyInAnyOrder(true, false);
        var stored = repository.findById(id).orElseThrow();
        assertThat(stored.getFailureCount())
            .isEqualTo(stored.getStatus() == NotificationOutboxStatus.PUBLISHED ? 0 : 1);
        assertThat(stored.getProcessingToken()).isNull();
        assertThat(stored.getProcessingUntil()).isNull();
    }

    @Test
    void 현재_담당의_해제는_이력을_보존하고_다음_확보에_새_토큰을_사용한다() {
        String token = claim().getProcessingToken();
        var before = withoutProcessing(row());

        assertThat(service.release(id, token)).isTrue();

        assertThat(withoutProcessing(row())).isEqualTo(before);
        assertThat(row()).containsEntry("processing_token", null).containsEntry("processing_until", null);
        assertThat(service.release(id, token)).isFalse();
        String next = claim().getProcessingToken();
        assertThat(next).isNotEqualTo(token);
        assertThat(service.release(id, token)).isFalse();
    }

    @Test
    void 실패_횟수가_변경되면_Repository의_조건부_실패_갱신을_거절한다() {
        String token = claim().getProcessingToken();
        var before = row();

        int updated = transaction.execute(tx -> repository.recordFailureByToken(id, token, 1, 2, FAILURE.name(),
            1_000_000L, "PENDING"));

        assertThat(updated).isZero();
        assertThat(row()).isEqualTo(before);
    }

    @Test
    void 외부_트랜잭션_롤백과_무관하게_확보와_실패와_성공과_해제가_각각_커밋된다() {
        String token = transaction.execute(tx -> {
            String assigned = claim().getProcessingToken();
            tx.setRollbackOnly();
            return assigned;
        });
        assertThat(repository.findById(id).orElseThrow().getProcessingToken()).isEqualTo(token);
        transaction.executeWithoutResult(tx -> {
            assertThat(failures.recordFailure(id, token, FAILURE)).isPresent();
            tx.setRollbackOnly();
        });
        assertThat(repository.findById(id).orElseThrow().getFailureCount()).isEqualTo(1);
        makeDue();
        String second = claim().getProcessingToken();
        transaction.executeWithoutResult(tx -> {
            assertThat(service.release(id, second)).isTrue();
            tx.setRollbackOnly();
        });
        assertThat(repository.findById(id).orElseThrow().getProcessingToken()).isNull();
        String third = claim().getProcessingToken();
        transaction.executeWithoutResult(tx -> {
            assertThat(service.markPublished(id, third)).isTrue();
            tx.setRollbackOnly();
        });
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(NotificationOutboxStatus.PUBLISHED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "failure", "release"})
    void 잠금_대기_중_담당이_만료되면_대기_이후의_DB_시각으로_결과와_해제를_거절한다(String operation)
        throws Exception {
        String token = claim().getProcessingToken();
        try (var holder = lockRow(); var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> switch (operation) {
                case "success" -> service.markPublished(id, token);
                case "failure" -> failures.recordFailure(id, token, FAILURE).isPresent();
                default -> service.release(id, token);
            });
            try {
                awaitLockWait(holder);
                try (var statement = holder.prepareStatement("""
                    UPDATE notification_request_outbox SET processing_until = UTC_TIMESTAMP(6) WHERE id = ?
                    """)) {
                    statement.setLong(1, id);
                    statement.executeUpdate();
                }
            } finally {
                holder.commit();
            }

            assertThat(result.get(5, TimeUnit.SECONDS)).isFalse();
        }
        var stored = repository.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(stored.getFailureCount()).isZero();
        assertThat(stored.getProcessingToken()).isEqualTo(token);
    }

    @Test
    void 담당_확보도_잠금_대기_이후의_DB_시각부터_유효기간을_계산한다() throws Exception {
        String token = claim().getProcessingToken();
        try (var holder = lockRow(); var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> service.tryClaim(id));
            LocalDateTime expiredAt;
            try {
                awaitLockWait(holder);
                try (var statement = holder.prepareStatement("""
                    UPDATE notification_request_outbox SET processing_until = UTC_TIMESTAMP(6) WHERE id = ?
                    """)) {
                    statement.setLong(1, id);
                    statement.executeUpdate();
                }
                expiredAt = dbNow();
            } finally {
                holder.commit();
            }

            var claimed = result.get(5, TimeUnit.SECONDS).orElseThrow();
            assertThat(claimed.getProcessingToken()).isNotEqualTo(token);
            assertThat(claimed.getProcessingUntil()).isBetween(expiredAt.plusSeconds(60), dbNow().plusSeconds(60));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"claim", "success", "failure", "release"})
    void 실제_DB_오류는_예외로_전달하고_담당과_이력을_함께_보존한다(String operation) {
        String token = operation.equals("claim") ? UUID.randomUUID().toString() : claim().getProcessingToken();
        var before = row();
        var administrator = new JdbcTemplate(
            new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
        administrator.execute("CREATE TRIGGER test_outbox_reject_update BEFORE UPDATE ON notification_request_outbox "
            + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'TEST_OUTBOX_UPDATE_REJECTED'");
        try {
            assertThatThrownBy(() -> {
                switch (operation) {
                    case "claim" -> service.tryClaim(id);
                    case "success" -> service.markPublished(id, token);
                    case "failure" -> failures.recordFailure(id, token, FAILURE);
                    default -> service.release(id, token);
                }
            }).isInstanceOf(DataAccessException.class);
            assertThat(row()).isEqualTo(before);
        } finally {
            administrator.execute("DROP TRIGGER test_outbox_reject_update");
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void 잘못된_ID는_에러코드로_거절한다(Long invalid) {
        String token = UUID.randomUUID().toString();
        assertInternalError(() -> service.tryClaim(invalid));
        assertInternalError(() -> service.markPublished(invalid, token));
        assertInternalError(() -> service.release(invalid, token));
        assertInternalError(() -> failures.recordFailure(invalid, token, FAILURE));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "short", "                                    "})
    void 누락되거나_길이가_다르거나_공백인_토큰은_에러코드로_거절한다(String token) {
        assertInternalError(() -> service.markPublished(id, token));
        assertInternalError(() -> service.release(id, token));
        assertInternalError(() -> failures.recordFailure(id, token, FAILURE));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MAX_VALUE})
    void 잘못되거나_마이크로초_변환이_불가능한_기간은_담당을_변경하지_않는다(long duration) {
        properties.setDurationMs(duration);
        var before = row();

        assertInternalError(() -> service.tryClaim(id));

        assertThat(row()).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(
        value = NotificationFailureCode.class,
        names = {"PUBLISH_INTERRUPTED", "OUTBOX_STATE_UPDATE_FAILED", "OUTBOX_RETRY_LIMIT_EXCEEDED"}
    )
    void 중단과_DB_오류와_소진_보고는_담당이_있어도_발행_실패로_계산하지_않는다(NotificationFailureCode code) {
        String token = claim().getProcessingToken();
        var before = row();

        assertInternalError(() -> failures.recordFailure(id, token, code));
        assertInternalError(() -> failures.recordFailure(id, token, null));

        assertThat(row()).isEqualTo(before);
    }

    @Test
    void 없는_행과_미담당_행은_결과와_해제의_대상이_아니다() {
        String token = UUID.randomUUID().toString();
        assertThat(service.tryClaim(Long.MAX_VALUE)).isEmpty();
        for (Long target : List.of(id, Long.MAX_VALUE)) {
            assertThat(service.markPublished(target, token)).isFalse();
            assertThat(failures.recordFailure(target, token, FAILURE)).isEmpty();
            assertThat(service.release(target, token)).isFalse();
        }
        assertThat(repository.findById(id).orElseThrow().getFailureCount()).isZero();
    }

    private NotificationRequestOutbox claim() {
        return service.tryClaim(id).orElseThrow();
    }

    private void expire() {
        jdbc.update("""
            UPDATE notification_request_outbox SET processing_until = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6))
            WHERE id = ?
            """, id);
    }

    private void makeDue() {
        jdbc.update("""
            UPDATE notification_request_outbox SET next_attempt_at = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6))
            WHERE id = ?
            """, id);
    }

    private LocalDateTime dbNow() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class);
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT * FROM notification_request_outbox WHERE id = ?", id);
    }

    private Map<String, Object> withoutProcessing(Map<String, Object> source) {
        var result = new LinkedHashMap<>(source);
        result.remove("processing_token");
        result.remove("processing_until");
        return result;
    }

    private void assertInternalError(Callable<?> action) {
        assertThatThrownBy(action::call).isInstanceOfSatisfying(NotificationBusinessException.class,
            failure -> assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR));
    }

    private <T> List<T> concurrently(Callable<T> first, Callable<T> second) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var futures = List.of(first, second).stream().map(action -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("TEST_START_TIMEOUT");
                }
                return action.call();
            })).toList();
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            return List.of(futures.getFirst().get(5, TimeUnit.SECONDS), futures.getLast().get(5, TimeUnit.SECONDS));
        }
    }

    private Connection lockRow() throws Exception {
        var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        try {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(
                "SELECT id FROM notification_request_outbox WHERE id = ? FOR UPDATE")) {
                statement.setLong(1, id);
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
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

    private void awaitLockWait(Connection holder) throws Exception {
        long connectionId;
        try (var statement = holder.createStatement(); var result = statement.executeQuery("SELECT CONNECTION_ID()")) {
            assertThat(result.next()).isTrue();
            connectionId = result.getLong(1);
        }
        var observer = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
            .untilAsserted(() -> assertThat(observer.queryForObject("""
                SELECT COUNT(*) FROM performance_schema.data_lock_waits waits
                JOIN performance_schema.threads holder ON holder.THREAD_ID = waits.BLOCKING_THREAD_ID
                WHERE holder.PROCESSLIST_ID = ?
                """, Integer.class, connectionId)).isPositive());
    }
}
