package com.team.dating_backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class NotificationCursorMigrationIntegrationTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 24, 12, 0, 0, 123_456_000);
    private static final String CURSOR_INDEX = "idx_notifications_user_deleted_created_id";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws SQLException {
        String schema = "notification_cursor_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
            var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + schema
                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        String url = MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + schema);
        dataSource = new DriverManagerDataSource(url, "root", MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void 새_DB는_V24와_V25를_순서대로_적용하고_재실행해도_변경하지_않는다() {
        Flyway flyway = migration(25);

        assertThat(flyway.migrate().migrationsExecuted).isPositive();

        assertThat(jdbc.queryForList("""
            SELECT version FROM flyway_schema_history
            WHERE version IN ('24', '25') ORDER BY installed_rank
            """, String.class)).containsExactly("24", "25");
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM flyway_schema_history
            WHERE script = 'V19__add_notification_source_event_id.sql'
            """, Integer.class)).isZero();
        assertIndexes();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void 기존_알림을_보존해_V24와_V25를_적용하고_중복_방지와_기존_인덱스를_유지한다() {
        migration(18).migrate();
        jdbc.update("""
            INSERT INTO users (id,birth_date,created_at,last_accessed_at,name,updated_at,
                face_verification_status,gender,persona_onboarding_status,status)
            VALUES (1,'2000-01-01',?,?, '보존회원',?, 'NOT_VERIFIED','FEMALE','PENDING','ACTIVE')
            """, CREATED_AT, CREATED_AT, CREATED_AT);
        jdbc.update("""
            INSERT INTO notifications (id,user_id,type,title,body,target_type,target_id,
                read_at,created_at,deleted_at)
            VALUES (11,1,'LIKE','첫 제목','첫 본문',NULL,NULL,?,?,NULL),
                (12,1,'LIKE','둘째 제목','둘째 본문','CHAT_ROOM',7,NULL,?,?)
            """, CREATED_AT.plusMinutes(1), CREATED_AT,
            CREATED_AT.minusNanos(1_000), CREATED_AT.plusMinutes(2));
        List<Map<String, Object>> legacy = legacyRows();

        assertThat(migration(24).migrate().migrationsExecuted).isPositive();

        assertThat(legacyRows()).isEqualTo(legacy);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM notifications WHERE source_event_id IS NULL
            """, Integer.class)).isEqualTo(2);
        assertThat(indexColumns(CURSOR_INDEX)).isEmpty();
        insertSourceNotification();
        List<Map<String, Object>> beforeIndex = jdbc.queryForList("SELECT * FROM notifications ORDER BY id");
        Flyway indexMigration = migration(25);

        assertThat(indexMigration.migrate().migrationsExecuted).isEqualTo(1);

        assertIndexes();
        assertThat(jdbc.queryForList("SELECT * FROM notifications ORDER BY id")).isEqualTo(beforeIndex);
        assertThatThrownBy(this::insertSourceNotification)
            .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                exception -> assertThat(exception.getMostSpecificCause())
                    .isInstanceOfSatisfying(SQLException.class, sql -> {
                        assertThat(sql.getErrorCode()).isEqualTo(1062);
                        assertThat(sql.getMessage()).contains("uk_notifications_source_recipient_type");
                    }));
        assertThat(indexMigration.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM notifications ORDER BY id")).isEqualTo(beforeIndex);
    }

    private Flyway migration(int version) {
        return Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/migration").target(Integer.toString(version))
            .baselineOnMigrate(false).outOfOrder(false).cleanDisabled(true).load();
    }

    private List<Map<String, Object>> legacyRows() {
        return jdbc.queryForList("""
            SELECT id,user_id,type,title,body,target_type,target_id,read_at,created_at,deleted_at
            FROM notifications ORDER BY id
            """);
    }

    private void insertSourceNotification() {
        jdbc.update("""
            INSERT INTO notifications (user_id,type,title,body,created_at,source_event_id)
            VALUES (1,'LIKE','원본 제목','원본 본문',?,'LIKE_SENT:400')
            """, CREATED_AT);
    }

    private void assertIndexes() {
        assertThat(indexColumns(CURSOR_INDEX))
            .containsExactly("user_id", "deleted_at", "created_at", "id");
        assertThat(indexColumns("idx_notifications_user_deleted_id"))
            .containsExactly("user_id", "deleted_at", "id");
        assertThat(indexColumns("uk_notifications_source_recipient_type"))
            .containsExactly("source_event_id", "user_id", "type");
    }

    private List<String> indexColumns(String name) {
        return jdbc.queryForList("""
            SELECT column_name FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'notifications' AND index_name = ?
            ORDER BY seq_in_index
            """, String.class, name);
    }
}
