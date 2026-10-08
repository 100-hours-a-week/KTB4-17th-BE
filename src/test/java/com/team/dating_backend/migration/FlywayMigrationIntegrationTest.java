package com.team.dating_backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class FlywayMigrationIntegrationTest {

    private static final String INITIAL_SCHEMA = "db/migration/V1__initial_schema.sql";
    private static final String NICKNAME_CORRECTION = "fixtures/db/adoption/restore_missing_active_nickname_index.sql";
    private static final String USERS_CORRECTION = "fixtures/db/adoption/align_users_with_development.sql";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private Flyway flyway;

    @BeforeEach
    void setUp() throws SQLException {
        // 테스트 컨테이너 안에만 DB를 생성한다. 외부 접속 정보는 읽지 않는다.
        String schema = "rehearsal_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(
            MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
            var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + schema
                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        String url = MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + schema);
        dataSource = new DriverManagerDataSource(url, "root", MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        flyway = Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/migration")
            // 공통 초기 스키마와 baseline 채택 절차는 V1을 기준으로 검증한다.
            .target("1")
            .baselineVersion("1").baselineOnMigrate(false).cleanDisabled(true).load();
    }

    @Test
    void 빈_DB에_공통_구조를_생성하고_재실행하면_변경하지_않는다() {
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);

        assertThat(count("tables", "table_name <> 'flyway_schema_history'"))
            .isEqualTo(23);
        assertThat(count("columns", "table_name <> 'flyway_schema_history'"))
            .isEqualTo(181);
        assertThat(count("table_constraints", "constraint_type = 'FOREIGN KEY'"))
            .isEqualTo(13);
        assertThat(count("table_constraints", "constraint_type = 'UNIQUE'"))
            .isEqualTo(18);
        assertThat(count("table_constraints", "constraint_type = 'CHECK'"))
            .isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void 공통_스키마는_이름_8자_제한과_페르소나_진행_상태를_지원한다() {
        flyway.migrate();
        insertUser(1);

        jdbc.update("UPDATE users SET name = ?, persona_onboarding_status = 'IN_PROGRESS' WHERE id = 1",
            "가나다라마바사아");

        assertThatThrownBy(() -> jdbc.update("UPDATE users SET name = ? WHERE id = 1", "가나다라마바사아자"))
            .rootCause().isInstanceOfSatisfying(SQLException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(1406));
        assertThat(jdbc.queryForObject("SELECT name FROM users WHERE id = 1", String.class))
            .isEqualTo("가나다라마바사아");
        assertThat(jdbc.queryForObject("SELECT persona_onboarding_status FROM users WHERE id = 1",
            String.class)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void 기존_운영_users를_개발_기준으로_보정하고_데이터를_보존한다() throws SQLException {
        executeScript(INITIAL_SCHEMA);
        List<Map<String, Object>> expectedColumns = columns();
        List<Map<String, Object>> expectedIndexes = indexes();
        jdbc.execute("""
            ALTER TABLE users
                MODIFY COLUMN name VARCHAR(8) NOT NULL AFTER withdrawn_at,
                MODIFY COLUMN persona_onboarding_status
                    ENUM('BYPASSED', 'CONFIRMED', 'PENDING') NOT NULL
            """);
        insertUser(1);
        insertProfile(1, "보존닉네임");
        List<Map<String, Object>> usersBefore = jdbc.queryForList("SELECT * FROM users ORDER BY id");

        executeScript(USERS_CORRECTION);

        assertThat(columns()).isEqualTo(expectedColumns);
        assertThat(indexes()).isEqualTo(expectedIndexes);
        assertThat(jdbc.queryForList("SELECT * FROM users ORDER BY id")).isEqualTo(usersBefore);
        assertThat(jdbc.queryForObject("SELECT nickname FROM profiles WHERE user_id = 1",
            String.class)).isEqualTo("보존닉네임");
        flyway.baseline();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        jdbc.update("UPDATE users SET persona_onboarding_status = 'IN_PROGRESS' WHERE id = 1");
    }

    @Test
    void 이력이_없는_기존_DB는_자동으로_baseline하지_않는다() throws SQLException {
        executeScript(INITIAL_SCHEMA);

        assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class)
            .hasMessageContaining("non-empty schema");
    }

    @Test
    void 명시적_baseline은_기존_구조와_데이터를_유지하고_V1을_건너뛴다()
        throws SQLException {
        executeScript(INITIAL_SCHEMA);
        insertUser(1);
        insertProfile(1, "보존닉네임");
        List<Map<String, Object>> columnsBefore = columns();
        List<Map<String, Object>> indexesBefore = indexes();

        flyway.baseline();

        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(columns()).isEqualTo(columnsBefore);
        assertThat(indexes()).isEqualTo(indexesBefore);
        assertThat(jdbc.queryForObject("SELECT nickname FROM profiles WHERE user_id = 1",
            String.class)).isEqualTo("보존닉네임");
        assertThat(jdbc.queryForObject("SELECT type FROM flyway_schema_history WHERE version = '1'",
            String.class)).isEqualTo("BASELINE");
    }

    @Test
    void 개발_DB의_인덱스_누락을_보정한_뒤_baseline한다() throws SQLException {
        executeScript(INITIAL_SCHEMA);
        jdbc.execute("ALTER TABLE profiles DROP INDEX uk_profiles_active_nickname");
        assertThat(activeNicknameIndexCount()).isZero();

        executeScript(NICKNAME_CORRECTION);
        flyway.baseline();

        assertThat(activeNicknameIndexCount()).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertNicknameConstraint();
    }

    @Test
    void 활성_닉네임은_유일하고_삭제된_닉네임과_NULL은_재사용할_수_있다() {
        flyway.migrate();

        assertNicknameConstraint();
    }

    @Test
    void 중복_데이터가_있으면_인덱스_보정은_실패하고_데이터를_보존한다()
        throws SQLException {
        executeScript(INITIAL_SCHEMA);
        jdbc.execute("ALTER TABLE profiles DROP INDEX uk_profiles_active_nickname");
        insertUser(1);
        insertUser(2);
        insertProfile(1, "중복닉네임");
        insertProfile(2, "중복닉네임");

        assertThatThrownBy(() -> executeScript(NICKNAME_CORRECTION))
            .hasRootCauseInstanceOf(SQLException.class);
        assertThat(activeNicknameIndexCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM profiles", Integer.class))
            .isEqualTo(2);
    }

    @Test
    void 이미_실행한_SQL의_변경을_체크섬으로_탐지한다(@TempDir Path directory)
        throws IOException {
        flyway.migrate();
        String sql = new ClassPathResource(INITIAL_SCHEMA)
            .getContentAsString(StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("V1__initial_schema.sql"), sql + "\nSELECT 1;\n");
        Flyway changed = Flyway.configure().dataSource(dataSource)
            .locations("filesystem:" + directory).cleanDisabled(true).load();

        assertThat(changed.validateWithResult().validationSuccessful).isFalse();
        assertThatThrownBy(changed::migrate).isInstanceOf(FlywayException.class)
            .hasMessageContaining("checksum mismatch");
    }

    @Test
    void baseline_다음_버전부터_새_변경을_실행한다(@TempDir Path directory)
        throws IOException, SQLException {
        executeScript(INITIAL_SCHEMA);
        insertUser(1);
        flyway.baseline();
        Files.writeString(directory.resolve("V1__initial_schema.sql"),
            new ClassPathResource(INITIAL_SCHEMA).getContentAsString(StandardCharsets.UTF_8));
        Files.writeString(directory.resolve("V2__rehearsal_marker.sql"),
            "CREATE TABLE rehearsal_marker (id BIGINT PRIMARY KEY);\n");
        Flyway nextVersion = Flyway.configure().dataSource(dataSource)
            .locations("filesystem:" + directory).cleanDisabled(true).load();

        assertThat(nextVersion.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(nextVersion.info().current().getVersion().getVersion()).isEqualTo("2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
        assertThat(nextVersion.migrate().migrationsExecuted).isZero();
    }

    @Test
    void 초기_SQL의_FK와_CHECK가_잘못된_데이터를_차단한다() {
        flyway.migrate();

        assertThatThrownBy(() -> insertProfile(999, "없는회원"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO activity_regions (representative_latitude, representative_longitude,
                created_at, updated_at, region_code, province_name, region_name)
            VALUES (91, 127, NOW(6), NOW(6), 'test', '검증시도', '검증지역')
            """)).rootCause().isInstanceOfSatisfying(SQLException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(3819));
    }

    @Test
    void clean을_차단한다() {
        flyway.migrate();

        assertThatThrownBy(flyway::clean).isInstanceOf(FlywayException.class)
            .hasMessageContaining("cleanDisabled");
        assertThat(count("tables", "table_name <> 'flyway_schema_history'"))
            .isEqualTo(23);
    }

    private void assertNicknameConstraint() {
        for (int id = 1; id <= 4; id++) {
            insertUser(id);
        }
        insertProfile(1, "재사용닉네임");
        assertThatThrownBy(() -> insertProfile(2, "재사용닉네임"))
            .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE profiles SET deleted_at = CURRENT_TIMESTAMP(6) WHERE user_id = 1");
        insertProfile(2, "재사용닉네임");
        insertProfile(3, null);
        insertProfile(4, null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM profiles", Integer.class))
            .isEqualTo(4);
    }

    private void insertUser(int id) {
        jdbc.update("""
            INSERT INTO users (id, birth_date, created_at, last_accessed_at, updated_at,
                name, face_verification_status, gender, persona_onboarding_status, status)
            VALUES (?, '1995-01-01', NOW(6), NOW(6), NOW(6),
                '검증회원', 'NOT_VERIFIED', 'MALE', 'PENDING', 'ONBOARDING')
            """, id);
    }

    private void insertProfile(int userId, String nickname) {
        jdbc.update("""
            INSERT INTO profiles (created_at, updated_at, user_id, nickname)
            VALUES (NOW(6), NOW(6), ?, ?)
            """, userId, nickname);
    }

    private int activeNicknameIndexCount() {
        return count("statistics",
            "table_name = 'profiles' AND index_name = 'uk_profiles_active_nickname' "
                + "AND non_unique = 0 AND expression IS NOT NULL");
    }

    private int count(String table, String predicate) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema." + table
            + " WHERE table_schema = DATABASE() AND " + predicate, Integer.class);
    }

    private List<Map<String, Object>> columns() {
        return jdbc.queryForList("""
            SELECT table_name, column_name, ordinal_position, column_type, is_nullable,
                column_default, extra, collation_name
            FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'
            ORDER BY table_name, ordinal_position
            """);
    }

    private List<Map<String, Object>> indexes() {
        return jdbc.queryForList("""
            SELECT table_name, index_name, non_unique, seq_in_index, column_name, expression
            FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'
            ORDER BY table_name, index_name, seq_in_index
            """);
    }

    private void executeScript(String resource) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(resource));
        }
    }
}
