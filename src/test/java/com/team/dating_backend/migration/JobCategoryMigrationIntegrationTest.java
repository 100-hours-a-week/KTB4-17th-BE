package com.team.dating_backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class JobCategoryMigrationIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Test
    void 기존_직업을_한번만_OTHER로_초기화하고_이후_수정값을_유지한다() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .target("11").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.update("""
            INSERT INTO users (id, birth_date, created_at, last_accessed_at, name, updated_at,
                face_verification_status, gender, persona_onboarding_status, status)
            VALUES
                (1, '2000-01-01', NOW(6), NOW(6), '회원1', NOW(6), 'NOT_VERIFIED', 'FEMALE', 'PENDING', 'ACTIVE'),
                (2, '2000-01-01', NOW(6), NOW(6), '회원2', NOW(6), 'NOT_VERIFIED', 'FEMALE', 'PENDING', 'ONBOARDING'),
                (3, '2000-01-01', NOW(6), NOW(6), '회원3', NOW(6), 'NOT_VERIFIED', 'FEMALE', 'PENDING', 'SUSPENDED'),
                (4, '2000-01-01', NOW(6), NOW(6), '회원4', NOW(6), 'NOT_VERIFIED', 'FEMALE', 'PENDING', 'WITHDRAWN')
            """);
        jdbc.update("""
            INSERT INTO profiles (id, user_id, created_at, updated_at, nickname, job, deleted_at)
            VALUES
                (1, 1, '2026-01-01', '2026-01-01', '회원1', '개발자', NULL),
                (2, 2, '2026-01-01', '2026-01-01', '회원2', NULL, NULL),
                (3, 3, '2026-01-01', '2026-01-01', '회원3', 'IT_DATA', NULL),
                (4, 4, '2026-01-01', '2026-01-01', '회원4', '우주정복자', '2026-01-02')
            """);
        var profilesBefore = jdbc
            .queryForList("SELECT id, user_id, nickname, updated_at, deleted_at FROM profiles ORDER BY id");
        var usersBefore = jdbc.queryForList("SELECT id, status, persona_onboarding_status FROM users ORDER BY id");

        Flyway flyway = Flyway.configure()
            .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT job FROM profiles ORDER BY id", String.class))
            .containsExactly("OTHER", "OTHER", "OTHER", "OTHER");
        assertThat(jdbc.queryForList("SELECT id, user_id, nickname, updated_at, deleted_at FROM profiles ORDER BY id"))
            .isEqualTo(profilesBefore);
        assertThat(jdbc.queryForList("SELECT id, status, persona_onboarding_status FROM users ORDER BY id"))
            .isEqualTo(usersBefore);
        assertThat(jdbc.queryForObject("""
            SELECT DATA_TYPE FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'profiles' AND column_name = 'job'
            """, String.class)).isEqualTo("varchar");

        jdbc.update("UPDATE profiles SET job = 'IT_DATA' WHERE id = 1");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT job FROM profiles WHERE id = 1", String.class))
            .isEqualTo("IT_DATA");
    }
}
