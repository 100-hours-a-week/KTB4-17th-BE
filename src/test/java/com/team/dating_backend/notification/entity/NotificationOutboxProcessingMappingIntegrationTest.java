package com.team.dating_backend.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.notification.enums.NotificationOutboxStatus;
import com.team.dating_backend.notification.enums.NotificationType;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
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
class NotificationOutboxProcessingMappingIntegrationTest {

    private static final String TOKEN = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    private static final String PAYLOAD = "{\"schemaVersion\":1,\"snapshot\":\"원본\"}";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 30, 12, 0, 0, 123456000);
    private static final LocalDateTime UNTIL = CREATED_AT.plusSeconds(60);

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private TestEntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 담당_식별값과_마이크로초_유효시각을_JPA로_복원하고_원본을_보존한다() {
        Long id = save();

        jdbc.update("""
            UPDATE notification_request_outbox SET processing_token = ?, processing_until = ? WHERE id = ?
            """, TOKEN, UNTIL, id);
        entityManager.clear();

        var stored = entityManager.find(NotificationRequestOutbox.class, id);
        assertThat(stored.getProcessingToken()).isEqualTo(TOKEN);
        assertThat(stored.getProcessingUntil()).isEqualTo(UNTIL);
        assertThat(stored.getProcessingUntil().getNano()).isEqualTo(123456000);
        assertThat(stored.getPayload()).isEqualTo(PAYLOAD);
        assertThat(stored.getSourceEventId()).isEqualTo("source");
        assertThat(stored.getRecipientUserId()).isEqualTo(7L);
        assertThat(stored.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(stored.getFailureCount()).isZero();
        assertThat(stored.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(stored.getNextAttemptAt()).isEqualTo(CREATED_AT);
    }

    @ParameterizedTest
    @MethodSource("invalidProcessing")
    void 불완전한_담당_정보와_완료_상태의_담당_정보를_DB에서_거부한다(
        String token, LocalDateTime until, NotificationOutboxStatus status) {
        Long id = save();

        assertThatThrownBy(() -> jdbc.update("""
            UPDATE notification_request_outbox
            SET processing_token = ?, processing_until = ?, status = ? WHERE id = ?
            """, token, until, status.name(), id))
            .isInstanceOfSatisfying(DataAccessException.class,
                failure -> assertThat(failure.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class,
                    sql -> {
                        assertThat(sql.getErrorCode()).isEqualTo(3819);
                        assertThat(sql.getMessage()).contains("chk_notification_outbox_processing");
                    }));

        entityManager.clear();
        var stored = entityManager.find(NotificationRequestOutbox.class, id);
        assertThat(stored.getProcessingToken()).isNull();
        assertThat(stored.getProcessingUntil()).isNull();
        assertThat(stored.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
    }

    @Test
    void 만료된_담당_정보가_있어도_조회만으로_실패_상태나_횟수가_변하지_않는다() {
        Long id = save();
        var expired = CREATED_AT.minusSeconds(1);
        jdbc.update("""
            UPDATE notification_request_outbox SET processing_token = ?, processing_until = ? WHERE id = ?
            """, TOKEN, expired, id);
        entityManager.clear();

        var stored = entityManager.find(NotificationRequestOutbox.class, id);

        assertThat(stored.getProcessingUntil()).isEqualTo(expired);
        assertThat(stored.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(stored.getFailureCount()).isZero();
    }

    @Test
    void 담당_정보는_두_필드를_함께_NULL로_정리할_수_있다() {
        Long id = save();
        jdbc.update("""
            UPDATE notification_request_outbox SET processing_token = ?, processing_until = ? WHERE id = ?
            """, TOKEN, UNTIL, id);

        jdbc.update("""
            UPDATE notification_request_outbox SET processing_token = NULL, processing_until = NULL WHERE id = ?
            """, id);
        entityManager.clear();

        var stored = entityManager.find(NotificationRequestOutbox.class, id);
        assertThat(stored.getProcessingToken()).isNull();
        assertThat(stored.getProcessingUntil()).isNull();
        assertThat(stored.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationOutboxStatus.class, names = {"PUBLISHED", "FAILED"})
    void 결과_기록과_담당_정보_정리를_하나의_갱신으로_수행할_수_있다(NotificationOutboxStatus status) {
        Long id = save();
        jdbc.update("""
            UPDATE notification_request_outbox SET processing_token = ?, processing_until = ? WHERE id = ?
            """, TOKEN, UNTIL, id);

        jdbc.update("""
            UPDATE notification_request_outbox
            SET status = ?, processing_token = NULL, processing_until = NULL,
                failure_count = ?, published_at = ? WHERE id = ?
            """, status.name(), status == NotificationOutboxStatus.FAILED ? 5 : 0,
            status == NotificationOutboxStatus.PUBLISHED ? UNTIL : null, id);
        entityManager.clear();

        var stored = entityManager.find(NotificationRequestOutbox.class, id);
        assertThat(stored.getStatus()).isEqualTo(status);
        assertThat(stored.getProcessingToken()).isNull();
        assertThat(stored.getProcessingUntil()).isNull();
    }

    @Test
    void 식별값은_ASCII_대소문자를_구분하고_유효시각은_6자리_정밀도를_사용한다() {
        Long id = save();
        jdbc.update("""
            UPDATE notification_request_outbox SET processing_token = ?, processing_until = ? WHERE id = ?
            """, TOKEN, UNTIL, id);

        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM notification_request_outbox WHERE processing_token = ?
            """, Long.class, TOKEN)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM notification_request_outbox WHERE processing_token = ?
            """, Long.class, TOKEN.toUpperCase(Locale.ROOT))).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT character_maximum_length FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'notification_request_outbox'
                AND column_name = 'processing_token'
            """, Integer.class)).isEqualTo(36);
        assertThat(jdbc.queryForObject("""
            SELECT collation_name FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'notification_request_outbox'
                AND column_name = 'processing_token'
            """, String.class)).isEqualTo("ascii_bin");
        assertThat(jdbc.queryForObject("""
            SELECT datetime_precision FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'notification_request_outbox'
                AND column_name = 'processing_until'
            """, Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForList("""
            SELECT column_name FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'notification_request_outbox'
                AND index_name = 'idx_notification_outbox_due'
            ORDER BY seq_in_index
            """, String.class)).containsExactly("status", "next_attempt_at", "id");
    }

    @Test
    void V29의_모든_상태와_원본을_보존하며_V30을_적용하고_재실행해도_변경하지_않는다() throws SQLException {
        String schema = "outbox_claim_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
            var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        try {
            String url = MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + schema);
            var source = new DriverManagerDataSource(url, "root", MYSQL.getPassword());
            var legacy = new JdbcTemplate(source);
            Flyway.configure().dataSource(source).target("29").load().migrate();
            for (var status : List.of(NotificationOutboxStatus.PENDING, NotificationOutboxStatus.PUBLISHED,
                NotificationOutboxStatus.FAILED)) {
                legacy.update("""
                    INSERT INTO notification_request_outbox
                        (source_event_id, recipient_user_id, type, payload, status, failure_count,
                         last_failure_type, next_attempt_at, created_at, published_at)
                    VALUES (?, 7, 'LIKE', ?, ?, ?, 'PUBLISH_RESULT_TIMEOUT', ?, ?, ?)
                    """, "legacy-" + status.name(), PAYLOAD, status.name(),
                    status == NotificationOutboxStatus.FAILED ? 5 : 1,
                    CREATED_AT.plusSeconds(2), CREATED_AT,
                    status == NotificationOutboxStatus.PUBLISHED ? CREATED_AT.plusSeconds(1) : null);
            }
            var before = legacy.queryForList("SELECT * FROM notification_request_outbox ORDER BY id");
            Flyway upgrade = Flyway.configure().dataSource(source).target("30").load();

            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);

            var after = legacy.queryForList("SELECT * FROM notification_request_outbox ORDER BY id");
            assertThat(after).hasSize(3);
            for (var row : after) {
                assertThat(row.remove("processing_token")).isNull();
                assertThat(row.remove("processing_until")).isNull();
            }
            assertThat(after).isEqualTo(before);
            assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
        } finally {
            try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + schema);
            }
        }
    }

    private Long save() {
        return entityManager.persistAndFlush(
            new NotificationRequestOutbox("source", 7L, NotificationType.LIKE, PAYLOAD,
                CREATED_AT.truncatedTo(ChronoUnit.MICROS)))
            .getId();
    }

    private static Stream<Arguments> invalidProcessing() {
        return Stream.of(
            Arguments.of(TOKEN, null, NotificationOutboxStatus.PENDING),
            Arguments.of(null, UNTIL, NotificationOutboxStatus.PENDING),
            Arguments.of("", UNTIL, NotificationOutboxStatus.PENDING),
            Arguments.of(" ".repeat(36), UNTIL, NotificationOutboxStatus.PENDING),
            Arguments.of("short", UNTIL, NotificationOutboxStatus.PENDING),
            Arguments.of(TOKEN, UNTIL, NotificationOutboxStatus.PUBLISHED),
            Arguments.of(TOKEN, UNTIL, NotificationOutboxStatus.FAILED),
            Arguments.of(TOKEN, UNTIL, NotificationOutboxStatus.COMPLETED));
    }
}
