package com.team.dating_backend.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class NotificationOutboxWorkerMigrationIntegrationTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 11, 11, 1, 2, 3, 123456000);
    private static final String PAYLOAD = "{\"sourceEventId\":\"original\",\"requestedAt\":\"2026-11-11T01:02:03.123456Z\"}";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 기존_기록을_보존하고_PUBLISHED가_남아_있으면_전환을_거부한다(boolean remainingPublished) throws Exception {
        String schema = "worker_cutover_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
            var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        try {
            String url = MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + schema);
            var source = new DriverManagerDataSource(url, "root", MYSQL.getPassword());
            var jdbc = new JdbcTemplate(source);
            Flyway.configure().dataSource(source).target("35").load().migrate();
            for (String status : List.of("PENDING", "FAILED", "COMPLETED")) {
                jdbc.update(
                    """
                        INSERT INTO notification_request_outbox
                            (source_event_id, recipient_user_id, type, payload, status, failure_count,
                             last_failure_type, next_attempt_at, created_at, completed_at, processing_token, processing_until)
                        VALUES (?, 7, 'LIKE', ?, ?, 3, 'OUTBOX_DELIVERY_FAILED', ?, ?, ?, ?, ?)
                        """,
                    "source-" + status, PAYLOAD, status, CREATED_AT.plusSeconds(5), CREATED_AT,
                    status.equals("COMPLETED") ? CREATED_AT.plusSeconds(3) : null,
                    status.equals("PENDING") ? UUID.randomUUID().toString() : null,
                    status.equals("PENDING") ? CREATED_AT.plusSeconds(60) : null);
            }
            if (remainingPublished) {
                jdbc.update("""
                    INSERT INTO notification_request_outbox
                        (source_event_id, recipient_user_id, type, payload, status, published_at)
                    VALUES ('legacy-published', 7, 'LIKE', ?, 'PUBLISHED', ?)
                    """, PAYLOAD, CREATED_AT);
            }
            var before = jdbc.queryForList("SELECT * FROM notification_request_outbox ORDER BY id");
            var upgrade = Flyway.configure().dataSource(source).target("36").load();

            if (remainingPublished) {
                assertThatThrownBy(upgrade::migrate).isInstanceOf(FlywayException.class)
                    .hasStackTraceContaining("chk_notification_outbox_status");
                assertThat(jdbc.queryForList("SELECT * FROM notification_request_outbox ORDER BY id"))
                    .isEqualTo(before);
                assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = 'notification_request_outbox'
                        AND column_name = 'published_at'
                    """, Integer.class)).isEqualTo(1);
            } else {
                assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
                before.forEach(row -> row.remove("published_at"));
                assertThat(jdbc.queryForList("SELECT * FROM notification_request_outbox ORDER BY id"))
                    .isEqualTo(before);
                assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = 'notification_request_outbox'
                        AND column_name = 'published_at'
                    """, Integer.class)).isZero();
                assertThatThrownBy(() -> jdbc.update("""
                    UPDATE notification_request_outbox SET status = 'PUBLISHED' WHERE status = 'FAILED'
                    """)).hasMessageContaining("chk_notification_outbox_status");
                assertThat(upgrade.migrate().migrationsExecuted).isZero();
            }
        } finally {
            try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + schema);
            }
        }
    }
}
