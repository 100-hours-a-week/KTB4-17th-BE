package com.team.dating_backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class RabbitOutboxMigrationIntegrationTest {

    private static final String BLUE_COLUMNS = """
        id, chat_message_id, created_at, next_attempt_at, failure_count,
        last_failure_type, published_at, failed_at
        """;

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws SQLException {
        // 외부 환경을 읽지 않고 테스트 컨테이너에만 스키마를 만든다.
        String schema = "rabbit_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(
            MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
            var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + schema
                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        String url = MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + schema);
        dataSource = new DriverManagerDataSource(url, "root", MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        migration("18").migrate();
    }

    @Test
    void V18에서_V21로_이행해도_Blue의_데이터와_계속되는_INSERT를_보존한다() {
        jdbc.update("""
            INSERT INTO chat_message_outbox
                (id, chat_message_id, created_at, next_attempt_at, failure_count,
                 published_at, failed_at, last_failure_type)
            VALUES
                (1, 1001, NOW(6), NOW(6), 2, '2026-10-01 01:00:00', NULL, 'BLUE_RETRY'),
                (2, 1002, NOW(6), NOW(6), 10, NULL, '2026-10-01 02:00:00', 'BLUE_FAILED'),
                (3, 1003, NOW(6), NOW(6), 0, NULL, NULL, NULL)
            """);
        List<Map<String, Object>> before = jdbc.queryForList(
            "SELECT " + BLUE_COLUMNS + " FROM chat_message_outbox ORDER BY id");
        Flyway next = migration("21");

        assertThat(next.migrate().migrationsExecuted).isEqualTo(3);

        assertThat(jdbc.queryForList(
            "SELECT " + BLUE_COLUMNS + " FROM chat_message_outbox ORDER BY id"))
            .isEqualTo(before);
        assertThat(jdbc.queryForObject("""
            SELECT rabbit_published_at FROM chat_message_outbox WHERE id = 1
            """, LocalDateTime.class)).isEqualTo(LocalDateTime.of(2026, 10, 1, 1, 0));
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM chat_message_outbox
            WHERE id IN (2, 3) AND rabbit_published_at IS NULL AND rabbit_failed_at IS NULL
            """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM chat_message_outbox
            WHERE rabbit_next_attempt_at IS NOT NULL AND rabbit_failure_count = 0
                AND rabbit_claim_token IS NULL AND rabbit_claim_until IS NULL
            """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'chat_message_outbox'
                AND column_name = 'rabbit_published'
            """, Integer.class)).isZero();
        assertThat(jdbc.queryForList("""
            SELECT column_name FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'chat_message_outbox'
                AND index_name = 'idx_chat_message_outbox_due' ORDER BY seq_in_index
            """, String.class)).containsExactly("published_at", "failed_at", "next_attempt_at", "id");

        // 실행 중인 Blue는 기존 컬럼만으로 계속 저장하고 발행할 수 있다.
        jdbc.update("""
            INSERT INTO chat_message_outbox
                (id, chat_message_id, created_at, next_attempt_at, failure_count)
            VALUES (4, 1004, NOW(6), NOW(6), 0)
            """);
        jdbc.update("UPDATE chat_message_outbox SET published_at = NOW(6) WHERE id = 4");
        assertThat(jdbc.queryForMap("""
            SELECT rabbit_published_at, rabbit_failed_at, rabbit_failure_count,
                rabbit_claim_token, rabbit_claim_until FROM chat_message_outbox WHERE id = 4
            """))
            .containsEntry("rabbit_published_at", null)
            .containsEntry("rabbit_failed_at", null)
            .containsEntry("rabbit_failure_count", 0)
            .containsEntry("rabbit_claim_token", null)
            .containsEntry("rabbit_claim_until", null);
        assertThat(next.info().current().getVersion().getVersion()).isEqualTo("21");
        assertThat(next.validateWithResult().validationSuccessful).isTrue();
        assertThat(next.migrate().migrationsExecuted).isZero();
    }

    private Flyway migration(String target) {
        return Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/migration").target(target)
            .baselineOnMigrate(false).cleanDisabled(true).load();
    }
}
