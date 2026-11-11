package com.team.dating_backend.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.notification.enums.NotificationOutboxStatus;
import com.team.dating_backend.notification.enums.NotificationType;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@Testcontainers
class NotificationOutboxCompletionMappingIntegrationTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 11, 9, 1, 2, 3, 123456000);
    private static final String PAYLOAD = "{\"snapshot\":\"원본\"}";
    private static final String TOKEN = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private TestEntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void Worker_완료_시각과_기존_Kafka_인계_시각을_독립적으로_저장하고_복원한다() {
        Long id = save();
        LocalDateTime publishedAt = CREATED_AT.plusSeconds(1);
        LocalDateTime completedAt = CREATED_AT.plusSeconds(5);

        jdbc.update("""
            UPDATE notification_request_outbox SET status = 'COMPLETED', published_at = ?, completed_at = ?
            WHERE id = ?
            """, publishedAt, completedAt, id);
        entityManager.clear();

        var stored = entityManager.find(NotificationRequestOutbox.class, id);
        assertThat(stored.getStatus()).isEqualTo(NotificationOutboxStatus.COMPLETED);
        assertThat(stored.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(stored.getCompletedAt()).isEqualTo(completedAt);
        assertThat(stored.getCompletedAt().getNano()).isEqualTo(123456000);
        assertThat(stored.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(stored.getPayload()).isEqualTo(PAYLOAD);
    }

    @Test
    void 생성_요청_Kafka_인계_시각_없이_Worker_완료를_저장한다() {
        Long id = save();
        LocalDateTime completedAt = CREATED_AT.plusSeconds(5);

        jdbc.update("""
            UPDATE notification_request_outbox SET status = 'COMPLETED', completed_at = ? WHERE id = ?
            """, completedAt, id);
        entityManager.clear();

        var stored = entityManager.find(NotificationRequestOutbox.class, id);
        assertThat(stored.getStatus()).isEqualTo(NotificationOutboxStatus.COMPLETED);
        assertThat(stored.getPublishedAt()).isNull();
        assertThat(stored.getCompletedAt()).isEqualTo(completedAt);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationOutboxStatus.class, names = {"PENDING", "PUBLISHED", "FAILED"})
    void 기존_상태는_Worker_완료_시각_없이_유지한다(NotificationOutboxStatus status) {
        Long id = save();
        LocalDateTime publishedAt = status == NotificationOutboxStatus.PUBLISHED ? CREATED_AT.plusSeconds(1) : null;

        jdbc.update("UPDATE notification_request_outbox SET status = ?, published_at = ? WHERE id = ?",
            status.name(), publishedAt, id);
        entityManager.clear();

        var stored = entityManager.find(NotificationRequestOutbox.class, id);
        assertThat(stored.getStatus()).isEqualTo(status);
        assertThat(stored.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(stored.getCompletedAt()).isNull();
    }

    @Test
    void 완료_시각은_nullable_datetime_6으로_구성한다() {
        var column = jdbc.queryForMap("""
            SELECT data_type, datetime_precision, is_nullable, column_default FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'notification_request_outbox'
                AND column_name = 'completed_at'
            """);

        assertThat(column).containsEntry("DATA_TYPE", "datetime")
            .containsEntry("IS_NULLABLE", "YES").containsEntry("COLUMN_DEFAULT", null);
        assertThat(((Number) column.get("DATETIME_PRECISION")).intValue()).isEqualTo(6);
    }

    @Test
    void V34의_원본과_실패와_점유_정보를_보존하며_V35를_적용한다() throws SQLException {
        String schema = "outbox_completion_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
            var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        try {
            String url = MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + schema);
            var source = new DriverManagerDataSource(url, "root", MYSQL.getPassword());
            var legacy = new JdbcTemplate(source);
            Flyway.configure().dataSource(source).target("34").load().migrate();
            for (var status : List.of(NotificationOutboxStatus.PENDING, NotificationOutboxStatus.PUBLISHED,
                NotificationOutboxStatus.FAILED)) {
                boolean pending = status == NotificationOutboxStatus.PENDING;
                legacy.update(
                    """
                        INSERT INTO notification_request_outbox
                            (source_event_id, recipient_user_id, type, payload, status, failure_count,
                             last_failure_type, next_attempt_at, created_at, published_at, processing_token, processing_until)
                        VALUES (?, 7, 'LIKE', ?, ?, ?, 'PUBLISH_RESULT_TIMEOUT', ?, ?, ?, ?, ?)
                        """,
                    "legacy-" + status.name(), PAYLOAD, status.name(),
                    status == NotificationOutboxStatus.FAILED ? 5 : 1,
                    CREATED_AT.plusSeconds(2), CREATED_AT,
                    status == NotificationOutboxStatus.PUBLISHED ? CREATED_AT.plusSeconds(1) : null,
                    pending ? TOKEN : null, pending ? CREATED_AT.plusSeconds(60) : null);
            }
            var before = legacy.queryForList("SELECT * FROM notification_request_outbox ORDER BY id");
            assertThatThrownBy(() -> legacy.update("""
                UPDATE notification_request_outbox SET status = 'COMPLETED' WHERE status = 'PUBLISHED'
                """)).isInstanceOf(DataAccessException.class).hasMessageContaining("chk_notification_outbox_status");
            Flyway upgrade = Flyway.configure().dataSource(source).target("35").load();

            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);

            var after = legacy.queryForList("SELECT * FROM notification_request_outbox ORDER BY id");
            assertThat(after).hasSize(3);
            for (var row : after) {
                assertThat(row).containsKey("completed_at");
                assertThat(row.remove("completed_at")).isNull();
            }
            assertThat(after).isEqualTo(before);
            assertThat(legacy.update("""
                UPDATE notification_request_outbox SET status = 'COMPLETED', completed_at = ?
                WHERE status = 'PUBLISHED'
                """, CREATED_AT.plusSeconds(5))).isEqualTo(1);
            var completed = legacy.queryForMap("SELECT * FROM notification_request_outbox WHERE status = 'COMPLETED'");
            assertThat(completed).containsEntry("published_at", CREATED_AT.plusSeconds(1))
                .containsEntry("completed_at", CREATED_AT.plusSeconds(5));
            var migrated = legacy.queryForList("SELECT * FROM notification_request_outbox ORDER BY id");
            assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
            assertThat(legacy.queryForList("SELECT * FROM notification_request_outbox ORDER BY id"))
                .isEqualTo(migrated);
        } finally {
            try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + schema);
            }
        }
    }

    private Long save() {
        return entityManager.persistAndFlush(
            new NotificationRequestOutbox("source", 7L, NotificationType.LIKE, PAYLOAD, CREATED_AT)).getId();
    }
}
