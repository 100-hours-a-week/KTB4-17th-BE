package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.chat.service.ChatMessageOutboxClaimService.ClaimedBatch;
import com.team.dating_backend.chat.service.ChatMessageOutboxClaimService.ClaimedOutbox;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class ChatMessageOutboxClaimIntegrationTest {

    private static final long LEASE_MS = 30_000;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final String BLUE_COLUMNS = """
        id, chat_message_id, created_at, next_attempt_at, failure_count,
        last_failure_type, published_at, failed_at
        """;
    private static final String V20_COLUMNS = BLUE_COLUMNS + """
        , rabbit_published_at, rabbit_next_attempt_at, rabbit_failure_count,
        rabbit_last_failure_type, rabbit_failed_at
        """;

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private AnnotationConfigApplicationContext context;
    private ChatMessageOutboxClaimService firstWorker;
    private ChatMessageOutboxClaimService secondWorker;

    @BeforeEach
    void setUp() throws SQLException {
        // 모든 DB 연결은 앱 설정 대신 격리된 테스트 컨테이너에서 가져온다.
        String schema = "outbox_claim_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(
            MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
            var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + schema
                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        String url = MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + schema);
        dataSource = new DriverManagerDataSource(url, "root", MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        migration("20").migrate();

        transactionManager = new DataSourceTransactionManager(dataSource);
        context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class);
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean("transactionManager", PlatformTransactionManager.class,
            () -> transactionManager);
        context.registerBean("firstWorker", ChatMessageOutboxClaimService.class,
            () -> new ChatMessageOutboxClaimService(jdbc, LEASE_MS));
        context.registerBean("secondWorker", ChatMessageOutboxClaimService.class,
            () -> new ChatMessageOutboxClaimService(jdbc, LEASE_MS));
        context.refresh();
        firstWorker = context.getBean("firstWorker", ChatMessageOutboxClaimService.class);
        secondWorker = context.getBean("secondWorker", ChatMessageOutboxClaimService.class);
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void V20에서_V21로_이행해도_기존_데이터와_Blue_INSERT를_보존한다() {
        insertRows(2);
        jdbc.update("""
            UPDATE chat_message_outbox SET rabbit_published_at = '2026-10-01 01:00:00',
                rabbit_last_failure_type = 'BEFORE_MIGRATION' WHERE id = 1
            """);
        jdbc.update("""
            UPDATE chat_message_outbox SET rabbit_failed_at = '2026-10-01 02:00:00',
                rabbit_failure_count = 7 WHERE id = 2
            """);
        List<Map<String, Object>> before = rows(V20_COLUMNS);
        List<Map<String, Object>> indexesBefore = indexes();
        Flyway next = migration("21");

        assertThat(next.migrate().migrationsExecuted).isEqualTo(1);

        assertThat(rows(V20_COLUMNS)).isEqualTo(before);
        assertThat(indexes()).isEqualTo(indexesBefore);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM chat_message_outbox
            WHERE rabbit_claim_token IS NULL AND rabbit_claim_until IS NULL
            """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForMap("""
            SELECT data_type, CAST(character_maximum_length AS SIGNED) AS character_maximum_length,
                is_nullable, column_default
            FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'chat_message_outbox'
                AND column_name = 'rabbit_claim_token'
            """))
            .containsEntry("DATA_TYPE", "char")
            .containsEntry("CHARACTER_MAXIMUM_LENGTH", 36L)
            .containsEntry("IS_NULLABLE", "YES")
            .containsEntry("COLUMN_DEFAULT", null);
        assertThat(jdbc.queryForMap("""
            SELECT data_type, CAST(datetime_precision AS SIGNED) AS datetime_precision,
                is_nullable, column_default
            FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'chat_message_outbox'
                AND column_name = 'rabbit_claim_until'
            """))
            .containsEntry("DATA_TYPE", "datetime")
            .containsEntry("DATETIME_PRECISION", 6L)
            .containsEntry("IS_NULLABLE", "YES")
            .containsEntry("COLUMN_DEFAULT", null);

        // 컬럼 추가 후에도 Blue는 기존 컬럼만으로 저장한다.
        jdbc.update("""
            INSERT INTO chat_message_outbox
                (id, chat_message_id, created_at, next_attempt_at, failure_count)
            VALUES (3, 1003, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 0)
            """);
        assertThat(row(3)).containsEntry("rabbit_claim_token", null)
            .containsEntry("rabbit_claim_until", null)
            .containsEntry("rabbit_published_at", null)
            .containsEntry("rabbit_failure_count", 0);
        assertThat(next.info().current().getVersion().getVersion()).isEqualTo("21");
        assertThat(next.validateWithResult().validationSuccessful).isTrue();
        assertThat(next.migrate().migrationsExecuted).isZero();
    }

    @Test
    void 두_worker가_동시에_각각_최대_100건을_중복없이_선점한다() throws Exception {
        migrateClaims();
        insertRows(250);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return firstWorker.claimBatch(1_000);
            });
            var second = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return secondWorker.claimBatch(100);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            ClaimedBatch firstBatch = first.get(10, TimeUnit.SECONDS);
            ClaimedBatch secondBatch = second.get(10, TimeUnit.SECONDS);

            assertThat(firstBatch.items()).hasSize(100);
            assertThat(secondBatch.items()).hasSize(100);
            assertThat(firstBatch.token()).isNotBlank().isNotEqualTo(secondBatch.token());
            assertThat(ids(firstBatch)).doesNotContainAnyElementsOf(ids(secondBatch));
            assertThat(ownedIds(firstBatch.token())).containsExactlyInAnyOrderElementsOf(ids(firstBatch));
            assertThat(ownedIds(secondBatch.token())).containsExactlyInAnyOrderElementsOf(ids(secondBatch));
            assertThat(firstWorker.claimBatch(100).items()).hasSize(50);
            assertThat(secondWorker.claimBatch(100).items()).isEmpty();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void 다른_트랜잭션이_잠근_첫_행을_기다리지_않고_다음_행을_선점한다() throws Exception {
        migrateClaims();
        insertRows(3);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (var locked = dataSource.getConnection()) {
            locked.setAutoCommit(false);
            try (var statement = locked.prepareStatement(
                "SELECT id FROM chat_message_outbox WHERE id = 1 FOR UPDATE")) {
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                }
                // 잠금을 기다리는 SELECT FOR UPDATE라면 여기서 시간 초과가 발생한다.
                ClaimedBatch batch = executor.submit(() -> firstWorker.claimBatch(100))
                    .get(5, TimeUnit.SECONDS);
                assertThat(ids(batch)).containsExactly(2L, 3L);
            } finally {
                locked.rollback();
            }
            assertThat(ids(secondWorker.claimBatch(100))).containsExactly(1L);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void 만료된_선점을_인계한_뒤_이전_token의_모든_갱신을_거부한다() {
        migrateClaims();
        insertRows(1);
        ClaimedBatch old = firstWorker.claimBatch(100);
        assertThat(secondWorker.claimBatch(100).items()).isEmpty();
        expireClaim(1);

        ClaimedBatch current = secondWorker.claimBatch(100);

        assertThat(ids(current)).containsExactly(1L);
        assertThat(current.token()).isNotEqualTo(old.token());
        Map<String, Object> beforeStaleWrites = row(1);
        assertThat(firstWorker.complete(1, old.token())).isFalse();
        assertThat(firstWorker.retry(1, old.token(), 60, "STALE_RETRY")).isFalse();
        assertThat(firstWorker.fail(1, old.token(), "STALE_FAILURE")).isFalse();
        assertThat(firstWorker.renew(old.token(), List.of(1L))).isZero();
        assertThat(row(1)).isEqualTo(beforeStaleWrites);
        assertThat(secondWorker.complete(1, current.token())).isTrue();
        assertThat(row(1)).containsEntry("rabbit_claim_token", null)
            .containsEntry("rabbit_claim_until", null);
    }

    @Test
    void 만료만으로_갱신할_수는_없지만_인계_전_늦은_confirm은_완료할_수_있다() {
        migrateClaims();
        insertRows(1);
        ClaimedBatch batch = firstWorker.claimBatch(100);
        expireClaim(1);

        assertThat(firstWorker.renew(batch.token(), List.of(1L))).isZero();
        assertThat(firstWorker.complete(1, batch.token())).isTrue();
        assertThat(row(1).get("rabbit_published_at")).isNotNull();
        assertThat(secondWorker.claimBatch(100).items()).isEmpty();
    }

    @Test
    void 미발행이고_재시도_시각이_도래한_미선점_또는_만료_행만_가져온다() {
        migrateClaims();
        insertRows(6);
        jdbc.update("UPDATE chat_message_outbox SET rabbit_published_at = CURRENT_TIMESTAMP(6) WHERE id = 2");
        jdbc.update("UPDATE chat_message_outbox SET rabbit_failed_at = CURRENT_TIMESTAMP(6) WHERE id = 3");
        jdbc.update("""
            UPDATE chat_message_outbox
            SET rabbit_next_attempt_at = ? WHERE id = 4
            """, LocalDateTime.now().plusHours(1));
        jdbc.update("""
            UPDATE chat_message_outbox SET rabbit_claim_token = ?,
                rabbit_claim_until = TIMESTAMPADD(HOUR, 1, CURRENT_TIMESTAMP(6)) WHERE id = 5
            """, UUID.randomUUID().toString());
        jdbc.update("""
            UPDATE chat_message_outbox SET rabbit_claim_token = ?,
                rabbit_claim_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6)) WHERE id = 6
            """, UUID.randomUUID().toString());

        ClaimedBatch batch = firstWorker.claimBatch(100);

        assertThat(ids(batch)).containsExactly(1L, 6L);
        assertThat(batch.items()).allSatisfy(item -> {
            assertThat(item.chatMessageId()).isEqualTo(item.id() + 1_000);
            assertThat(item.createdAt()).isEqualTo(CREATED_AT);
            assertThat(item.rabbitFailureCount()).isEqualTo(3);
        });
        assertThat(secondWorker.claimBatch(100).items()).isEmpty();
    }

    @Test
    void 갱신은_아직_유효한_자기_선점만_연장하고_완료_행은_건드리지_않는다() {
        migrateClaims();
        insertRows(4);
        ClaimedBatch batch = firstWorker.claimBatch(3);
        ClaimedBatch other = secondWorker.claimBatch(1);
        jdbc.update("""
            UPDATE chat_message_outbox
            SET rabbit_claim_until = TIMESTAMPADD(SECOND, 10, CURRENT_TIMESTAMP(6)) WHERE id = 1
            """);
        LocalDateTime beforeRenewal = timestamp(1, "rabbit_claim_until");
        expireClaim(2);
        assertThat(firstWorker.complete(3, batch.token())).isTrue();
        Map<String, Object> expired = row(2);
        Map<String, Object> completed = row(3);
        Map<String, Object> otherOwner = row(4);
        LocalDateTime databaseBeforeRenewal = databaseNow();

        assertThat(firstWorker.renew(batch.token(), List.of(1L, 2L, 3L, 4L))).isEqualTo(1);

        assertThat(timestamp(1, "rabbit_claim_until")).isAfter(beforeRenewal);
        assertThat(timestamp(1, "rabbit_claim_until"))
            .isBetween(databaseBeforeRenewal.plusSeconds(30), databaseNow().plusSeconds(30));
        assertThat(row(2)).isEqualTo(expired);
        assertThat(row(3)).isEqualTo(completed);
        assertThat(row(4)).isEqualTo(otherOwner);
        assertThat(ownedIds(other.token())).containsExactly(4L);
        assertThat(firstWorker.renew(batch.token(), List.of())).isZero();
    }

    @Test
    void 완료_재시도_실패는_Blue_컬럼을_보존하며_실패횟수를_한번만_증가시킨다() {
        migrateClaims();
        insertRows(3);
        List<Map<String, Object>> blueBefore = rows(BLUE_COLUMNS);
        ClaimedBatch batch = firstWorker.claimBatch(100);
        LocalDateTime before = LocalDateTime.now();

        assertThat(firstWorker.complete(1, batch.token())).isTrue();
        assertThat(firstWorker.retry(2, batch.token(), 60, "BROKER_TIMEOUT")).isTrue();
        assertThat(firstWorker.fail(3, batch.token(), "MESSAGE_NOT_FOUND")).isTrue();

        LocalDateTime after = LocalDateTime.now();
        assertThat(rows(BLUE_COLUMNS)).isEqualTo(blueBefore);
        assertThat(row(1)).containsEntry("rabbit_failure_count", 3)
            .containsEntry("rabbit_last_failure_type", null);
        assertThat(timestamp(1, "rabbit_published_at")).isBetween(before, after);
        assertThat(row(2)).containsEntry("rabbit_failure_count", 4)
            .containsEntry("rabbit_last_failure_type", "BROKER_TIMEOUT")
            .containsEntry("rabbit_published_at", null)
            .containsEntry("rabbit_failed_at", null);
        assertThat(timestamp(2, "rabbit_next_attempt_at"))
            .isBetween(before.plusSeconds(60), after.plusSeconds(60));
        assertThat(row(3)).containsEntry("rabbit_failure_count", 4)
            .containsEntry("rabbit_last_failure_type", "MESSAGE_NOT_FOUND")
            .containsEntry("rabbit_published_at", null);
        assertThat(timestamp(3, "rabbit_failed_at")).isBetween(before, after);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM chat_message_outbox
            WHERE rabbit_claim_token IS NULL AND rabbit_claim_until IS NULL
            """, Integer.class)).isEqualTo(3);

        List<Map<String, Object>> once = rows("*");
        assertThat(firstWorker.complete(1, batch.token())).isFalse();
        assertThat(firstWorker.retry(2, batch.token(), 60, "DUPLICATE_CALLBACK")).isFalse();
        assertThat(firstWorker.fail(3, batch.token(), "DUPLICATE_CALLBACK")).isFalse();
        assertThat(rows("*")).isEqualTo(once);
        assertThat(secondWorker.claimBatch(100).items()).isEmpty();
    }

    @Test
    void 선점은_호출자_트랜잭션과_별도로_commit되어_롤백_후에도_보인다() {
        migrateClaims();
        insertRows(1);
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        ClaimedBatch batch = outer.execute(status -> {
            ClaimedBatch claimed = firstWorker.claimBatch(100);
            status.setRollbackOnly();
            return claimed;
        });

        assertThat(batch).isNotNull();
        assertThat(ownedIds(batch.token())).containsExactly(1L);
        assertThat(secondWorker.claimBatch(100).items()).isEmpty();
    }

    private Flyway migration(String version) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
            .target(version).baselineOnMigrate(false).cleanDisabled(true).load();
    }

    private void migrateClaims() {
        migration("21").migrate();
    }

    private void insertRows(int count) {
        List<Object[]> arguments = new ArrayList<>();
        for (long id = 1; id <= count; id++) {
            arguments.add(new Object[]{id, id + 1_000, CREATED_AT});
        }
        jdbc.batchUpdate("""
            INSERT INTO chat_message_outbox
                (id, chat_message_id, created_at, next_attempt_at, failure_count,
                last_failure_type, published_at, rabbit_next_attempt_at,
                rabbit_failure_count, rabbit_last_failure_type)
            VALUES (?, ?, ?, '2026-10-01 00:00:01', 9, 'BLUE_HISTORY',
                '2026-10-01 00:00:02', '2020-01-01 00:00:00', 3, 'PREVIOUS_RETRY')
            """, arguments);
    }

    private List<Long> ids(ClaimedBatch batch) {
        return batch.items().stream().map(ClaimedOutbox::id).toList();
    }

    private List<Long> ownedIds(String token) {
        return jdbc.queryForList("""
            SELECT id FROM chat_message_outbox WHERE rabbit_claim_token = ? ORDER BY id
            """, Long.class, token);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM chat_message_outbox WHERE id = ?", id);
    }

    private List<Map<String, Object>> rows(String columns) {
        return jdbc.queryForList("SELECT " + columns + " FROM chat_message_outbox ORDER BY id");
    }

    private List<Map<String, Object>> indexes() {
        return jdbc.queryForList("""
            SELECT index_name, seq_in_index, column_name, non_unique
            FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'chat_message_outbox'
            ORDER BY index_name, seq_in_index
            """);
    }

    private void expireClaim(long id) {
        jdbc.update("""
            UPDATE chat_message_outbox
            SET rabbit_claim_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6)) WHERE id = ?
            """, id);
    }

    private LocalDateTime databaseNow() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)",
            (row, index) -> row.getTimestamp(1).toLocalDateTime());
    }

    private LocalDateTime timestamp(long id, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM chat_message_outbox WHERE id = ?",
            (row, index) -> row.getTimestamp(1).toLocalDateTime(), id);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {}
}
