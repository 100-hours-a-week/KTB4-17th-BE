package com.team.dating_backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class RecommendationRandomKeyMigrationIntegrationTest {

    private static final String RANDOM_KEY_INDEX = "idx_users_recommendation_random_key_id";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Test
    void 기존_회원에게_추천_랜덤_키를_부여하고_복합_인덱스를_생성한다() {
        migration("37").migrate();
        JdbcTemplate jdbc = jdbcTemplate();
        jdbc.update("""
            INSERT INTO users (id, birth_date, created_at, last_accessed_at, name, updated_at,
                face_verification_status, gender, persona_onboarding_status, status)
            VALUES
                (1, '2000-01-01', NOW(6), NOW(6), '회원1', NOW(6),
                    'NOT_VERIFIED', 'FEMALE', 'PENDING', 'ACTIVE'),
                (2, '2000-01-01', NOW(6), NOW(6), '회원2', NOW(6),
                    'NOT_VERIFIED', 'MALE', 'PENDING', 'ACTIVE'),
                (3, '2000-01-01', NOW(6), NOW(6), '회원3', NOW(6),
                    'NOT_VERIFIED', 'FEMALE', 'PENDING', 'ACTIVE')
            """);

        Flyway current = migration("38");
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);

        List<Long> randomKeys = jdbc.queryForList(
            "SELECT recommendation_random_key FROM users ORDER BY id", Long.class);
        assertThat(randomKeys)
            .hasSize(3)
            .allSatisfy(key -> assertThat(key).isNotNull().isNotNegative());
        assertThat(jdbc.queryForObject("""
            SELECT is_nullable
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'users'
              AND column_name = 'recommendation_random_key'
            """, String.class)).isEqualTo("NO");
        assertThat(jdbc.queryForList("""
            SELECT column_name
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'users'
              AND index_name = ?
            ORDER BY seq_in_index
            """, String.class, RANDOM_KEY_INDEX))
            .containsExactly("recommendation_random_key", "id");

        assertThat(current.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList(
            "SELECT recommendation_random_key FROM users ORDER BY id", Long.class))
            .isEqualTo(randomKeys);
    }

    private Flyway migration(String version) {
        return Flyway.configure()
            .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .target(version)
            .load();
    }

    private JdbcTemplate jdbcTemplate() {
        return new JdbcTemplate(new DriverManagerDataSource(
            MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    }
}
