package com.team.dating_backend.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
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
    void PUBLISHED_행을_보존해_재처리하고_V36_이력이_있어도_스키마를_정리한다(boolean restoreStaleV36Schema)
        throws Exception {
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
            insertPublished(jdbc, "legacy-published");

            var v36 = Flyway.configure().dataSource(source).target("36").load();
            assertThat(v36.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(status(jdbc, "legacy-published")).isEqualTo("PENDING");
            assertThat(failureCount(jdbc, "legacy-published")).isZero();
            assertThat(countByStatus(jdbc, "PUBLISHED")).isZero();
            assertThat(columnExists(jdbc, "published_at")).isFalse();

            if (restoreStaleV36Schema) {
                jdbc.execute("""
                    ALTER TABLE notification_request_outbox
                    DROP CHECK chk_notification_outbox_status,
                    ADD COLUMN published_at datetime(6) DEFAULT NULL,
                    ADD CONSTRAINT chk_notification_outbox_status
                        CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED', 'COMPLETED'))
                    """);
                insertPublished(jdbc, "legacy-published-after-v36");
            }

            var v37 = Flyway.configure().dataSource(source).target("37").load();
            assertThat(v37.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(countByStatus(jdbc, "PUBLISHED")).isZero();
            assertThat(status(jdbc, "legacy-published")).isEqualTo("PENDING");
            assertThat(failureCount(jdbc, "legacy-published")).isZero();
            if (restoreStaleV36Schema) {
                assertThat(status(jdbc, "legacy-published-after-v36")).isEqualTo("PENDING");
                assertThat(failureCount(jdbc, "legacy-published-after-v36")).isZero();
            }
            assertThat(columnExists(jdbc, "completed_at")).isTrue();
            assertThat(columnExists(jdbc, "published_at")).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_request_outbox", Integer.class))
                .isEqualTo(restoreStaleV36Schema ? 5 : 4);
            assertThatThrownBy(() -> jdbc.update("""
                UPDATE notification_request_outbox SET status = 'PUBLISHED' WHERE status = 'FAILED'
                """)).hasMessageContaining("chk_notification_outbox_status");
            assertThat(v37.migrate().migrationsExecuted).isZero();
        } finally {
            try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + schema);
            }
        }
    }

    private void insertPublished(JdbcTemplate jdbc, String sourceEventId) {
        jdbc.update("""
            INSERT INTO notification_request_outbox
                (source_event_id, recipient_user_id, type, payload, status, failure_count, last_failure_type, published_at)
            VALUES (?, 7, 'LIKE', ?, 'PUBLISHED', 3, 'OUTBOX_DELIVERY_FAILED', ?)
            """, sourceEventId, PAYLOAD, CREATED_AT);
    }

    private String status(JdbcTemplate jdbc, String sourceEventId) {
        return jdbc.queryForObject("""
            SELECT status FROM notification_request_outbox WHERE source_event_id = ?
            """, String.class, sourceEventId);
    }

    private int countByStatus(JdbcTemplate jdbc, String status) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM notification_request_outbox WHERE status = ?
            """, Integer.class, status);
    }

    private int failureCount(JdbcTemplate jdbc, String sourceEventId) {
        return jdbc.queryForObject("""
            SELECT failure_count FROM notification_request_outbox WHERE source_event_id = ?
            """, Integer.class, sourceEventId);
    }

    private boolean columnExists(JdbcTemplate jdbc, String columnName) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'notification_request_outbox' AND column_name = ?
            """, Integer.class, columnName) == 1;
    }
}
