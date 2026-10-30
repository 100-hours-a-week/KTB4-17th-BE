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
import java.util.UUID;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@Testcontainers
class NotificationRequestOutboxMappingIntegrationTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 30, 12, 0, 0, 123456789);
    private static final String PAYLOAD = """
        {"schemaVersion":1,"sourceEventId":"LIKE_SENT:501","recipientUserId":7,"type":"LIKE",
         "title":"새로운 좋아요😀","body":"하늘님이 마음을 전했어요.","targetType":"PROFILE","targetId":8,
         "requestedAt":"2026-10-30T01:02:03.123456789Z"}
        """;

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private TestEntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void JPA_저장과_재조회에서_요청_스냅샷과_초기_상태를_보존한다() {
        var original = new NotificationRequestOutbox("LIKE_SENT:501", 7L, NotificationType.LIKE, PAYLOAD, CREATED_AT);
        Long id = entityManager.persistAndFlush(original).getId();
        entityManager.clear();

        var stored = entityManager.find(NotificationRequestOutbox.class, id);

        assertThat(stored.getSourceEventId()).isEqualTo("LIKE_SENT:501");
        assertThat(stored.getRecipientUserId()).isEqualTo(7);
        assertThat(stored.getType()).isEqualTo(NotificationType.LIKE);
        assertThat(stored.getPayload()).isEqualTo(PAYLOAD);
        assertThat(stored.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(stored.getFailureCount()).isZero();
        assertThat(stored.getLastFailureType()).isNull();
        assertThat(stored.getPublishedAt()).isNull();
        assertThat(stored.getCreatedAt()).isEqualTo(CREATED_AT.truncatedTo(ChronoUnit.MICROS));
        assertThat(stored.getNextAttemptAt()).isEqualTo(stored.getCreatedAt());
    }

    @Test
    void DB_기본값도_발행대기와_실패_0회_및_즉시_시도_가능_상태다() {
        insert("LIKE_SENT:501", 7, "LIKE");
        var row = jdbc.queryForMap("SELECT * FROM notification_request_outbox");

        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("failure_count")).isEqualTo(0);
        assertThat(row.get("last_failure_type")).isNull();
        assertThat(row.get("published_at")).isNull();
        assertThat(row.get("created_at")).isNotNull().isEqualTo(row.get("next_attempt_at"));
    }

    @Test
    void 동일_사건의_수신자와_유형을_구분하고_완전히_같은_요청만_중복으로_거부한다() {
        insert("LIKE_SENT:501", 7, "LIKE");
        insert("LIKE_SENT:501", 8, "LIKE");
        insert("LIKE_SENT:501", 7, "MATCH");
        insert("Like_SENT:501", 7, "LIKE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_request_outbox", Long.class)).isEqualTo(4);

        assertThatThrownBy(() -> insert("LIKE_SENT:501", 7, "LIKE"))
            .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                failure -> assertThat(failure.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class,
                    sql -> {
                        assertThat(sql.getErrorCode()).isEqualTo(1062);
                        assertThat(sql.getMessage()).contains("uk_notification_outbox_source_recipient_type");
                    }));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "😀"})
    void 기존_메시지_계약의_255자_이벤트_식별자를_저장한다(String character) {
        String source = character.repeat(255);
        var original = new NotificationRequestOutbox(source, 7L, NotificationType.LIKE, PAYLOAD, CREATED_AT);
        Long id = entityManager.persistAndFlush(original).getId();
        entityManager.clear();

        assertThat(entityManager.find(NotificationRequestOutbox.class, id).getSourceEventId()).isEqualTo(source);
    }

    @Test
    void 알림_유형과_발행_상태를_문자열로_저장하고_enum으로_복원한다() {
        for (var type : NotificationType.values()) {
            var entry = new NotificationRequestOutbox("EVENT:" + type.name(), 7L, type, PAYLOAD, CREATED_AT);
            Long id = entityManager.persistAndFlush(entry).getId();
            assertThat(jdbc.queryForObject("SELECT type FROM notification_request_outbox WHERE id = ?",
                String.class, id)).isEqualTo(type.name());
            for (var status : NotificationOutboxStatus.values()) {
                jdbc.update("UPDATE notification_request_outbox SET status = ? WHERE id = ?", status.name(), id);
                entityManager.clear();
                assertThat(entityManager.find(NotificationRequestOutbox.class, id).getStatus()).isEqualTo(status);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("invalidColumns")
    void 잘못된_상태와_실패횟수_수신자_JSON을_DB에서_거부한다(String column, Object value, String constraint) {
        insert("LIKE_SENT:501", 7, "LIKE");

        assertThatThrownBy(() -> jdbc.update("UPDATE notification_request_outbox SET " + column + " = ?", value))
            .isInstanceOfSatisfying(DataAccessException.class,
                failure -> assertThat(failure.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class,
                    sql -> {
                        assertThat(sql.getErrorCode()).isEqualTo(3819);
                        assertThat(sql.getMessage()).contains(constraint);
                    }));
    }

    @Test
    void 고유_인덱스와_발행대상_조회_인덱스_및_식별자_collation을_확인한다() {
        assertThat(indexColumns("uk_notification_outbox_source_recipient_type"))
            .containsExactly("source_event_id", "recipient_user_id", "type");
        assertThat(indexColumns("idx_notification_outbox_due"))
            .containsExactly("status", "next_attempt_at", "id");
        String collation = jdbc.queryForObject("""
            SELECT collation_name FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'notification_request_outbox'
                AND column_name = 'source_event_id'
            """, String.class);
        assertThat(collation).isEqualTo(jdbc.queryForObject("""
            SELECT collation_name FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'notifications' AND column_name = 'source_event_id'
            """, String.class));
    }

    @Test
    void V28에서_기존_알림을_보존하며_V29를_추가하고_재실행해도_변경하지_않는다() throws SQLException {
        String schema = "outbox_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
            var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        String url = MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + schema);
        var source = new DriverManagerDataSource(url, "root", MYSQL.getPassword());
        var legacy = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).target("28").load().migrate();
        legacy.update("""
            INSERT INTO users (id,birth_date,created_at,last_accessed_at,name,updated_at,
                face_verification_status,gender,persona_onboarding_status,status)
            VALUES (1,'2000-01-01',?,?, '기존회원',?, 'NOT_VERIFIED','FEMALE','PENDING','ACTIVE')
            """, CREATED_AT, CREATED_AT, CREATED_AT);
        legacy.update("""
            INSERT INTO notifications (user_id,type,title,body,created_at,read_at,deleted_at,source_event_id)
            VALUES (1,'LIKE','기존 제목','기존 본문',?,?,?,'LIKE_SENT:legacy')
            """, CREATED_AT, CREATED_AT.plusMinutes(1), CREATED_AT.plusMinutes(2));
        var before = legacy.queryForList("SELECT * FROM notifications ORDER BY id");
        Flyway upgrade = Flyway.configure().dataSource(source).target("29").load();

        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(legacy.queryForList("SELECT * FROM notifications ORDER BY id")).isEqualTo(before);
        assertThat(legacy.queryForObject("SELECT COUNT(*) FROM notification_request_outbox", Long.class)).isZero();
        assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
    }

    private void insert(String sourceEventId, long recipient, String type) {
        jdbc.update("""
            INSERT INTO notification_request_outbox (source_event_id, recipient_user_id, type, payload)
            VALUES (?, ?, ?, ?)
            """, sourceEventId, recipient, type, PAYLOAD);
    }

    private List<String> indexColumns(String name) {
        return jdbc.queryForList("""
            SELECT column_name FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'notification_request_outbox' AND index_name = ?
            ORDER BY seq_in_index
            """, String.class, name);
    }

    private static Stream<Arguments> invalidColumns() {
        return Stream.of(
            Arguments.of("status", "UNKNOWN", "chk_notification_outbox_status"),
            Arguments.of("status", "pending", "chk_notification_outbox_status"),
            Arguments.of("failure_count", -1, "chk_notification_outbox_failure_count"),
            Arguments.of("recipient_user_id", 0, "chk_notification_outbox_recipient"),
            Arguments.of("payload", "invalid-json", "chk_notification_outbox_payload"));
    }
}
