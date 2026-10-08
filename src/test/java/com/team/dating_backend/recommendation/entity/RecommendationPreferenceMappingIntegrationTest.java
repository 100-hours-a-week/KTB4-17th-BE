package com.team.dating_backend.recommendation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(
    properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
    }
)
@Testcontainers
class RecommendationPreferenceMappingIntegrationTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 7, 10, 0);

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 회원_ID를_기본키로_사용하고_미선택_조건은_빈_JSON_배열로_저장한다() {
        User user = persistUser();
        RecommendationPreference preference = new RecommendationPreference(user, CREATED_AT);

        entityManager.persist(preference);
        entityManager.flush();
        entityManager.clear();

        RecommendationPreference saved = entityManager.find(RecommendationPreference.class, user.getId());
        assertThat(saved.getUserId()).isEqualTo(user.getId());
        assertThat(saved.getUser().getId()).isEqualTo(user.getId());
        assertThat(saved.getReligion()).isEmpty();
        assertThat(saved.getDrinking()).isEmpty();
        assertThat(saved.getSmoking()).isEmpty();
        assertThat(saved.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(saved.getUpdatedAt()).isEqualTo(CREATED_AT);
        assertThat(jdbcTemplate.queryForMap("""
            SELECT JSON_TYPE(religion) AS religion_type,
                JSON_TYPE(drinking) AS drinking_type,
                JSON_TYPE(smoking) AS smoking_type,
                JSON_LENGTH(religion) AS religion_count,
                JSON_LENGTH(drinking) AS drinking_count,
                JSON_LENGTH(smoking) AS smoking_count
            FROM recommendation_preferences WHERE user_id = ?
            """, user.getId()))
            .containsEntry("religion_type", "ARRAY")
            .containsEntry("drinking_type", "ARRAY")
            .containsEntry("smoking_type", "ARRAY")
            .containsEntry("religion_count", 0L)
            .containsEntry("drinking_count", 0L)
            .containsEntry("smoking_count", 0L);
    }

    @Test
    void 복수_선택값을_Enum_문자열_JSON_배열로_저장하고_같은_타입으로_조회한다() {
        User user = persistUser();
        List<Religion> religions = List.of(Religion.CATHOLIC, Religion.NONE);
        List<Drinking> drinking = List.of(Drinking.NEVER, Drinking.SOCIAL);
        List<Smoking> smoking = List.of(Smoking.NON_SMOKER, Smoking.OCCASIONAL);
        RecommendationPreference preference = new RecommendationPreference(user, religions, drinking, smoking,
            CREATED_AT);

        entityManager.persist(preference);
        entityManager.flush();
        entityManager.clear();

        RecommendationPreference saved = entityManager.find(RecommendationPreference.class, user.getId());
        assertThat(saved.getReligion()).containsExactlyElementsOf(religions);
        assertThat(saved.getDrinking()).containsExactlyElementsOf(drinking);
        assertThat(saved.getSmoking()).containsExactlyElementsOf(smoking);
        assertThat(jdbcTemplate.queryForMap("""
            SELECT JSON_UNQUOTE(JSON_EXTRACT(religion, '$[0]')) AS religion_value,
                JSON_UNQUOTE(JSON_EXTRACT(drinking, '$[0]')) AS drinking_value,
                JSON_UNQUOTE(JSON_EXTRACT(smoking, '$[0]')) AS smoking_value
            FROM recommendation_preferences WHERE user_id = ?
            """, user.getId()))
            .containsEntry("religion_value", "CATHOLIC")
            .containsEntry("drinking_value", "NEVER")
            .containsEntry("smoking_value", "NON_SMOKER");
    }

    @Test
    void 선호_조건_삭제가_회원_삭제로_전파되지_않는다() {
        User user = persistUser();
        RecommendationPreference preference = new RecommendationPreference(user, CREATED_AT);
        entityManager.persist(preference);
        entityManager.flush();

        entityManager.remove(preference);
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(RecommendationPreference.class, user.getId())).isNull();
        assertThat(entityManager.find(User.class, user.getId())).isNotNull();
    }

    @Test
    void 회원이_없으면_DB_외래키가_선호_조건_생성을_거절한다() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO recommendation_preferences (user_id) VALUES (?)
            """, Long.MAX_VALUE))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 선택값을_변경하면_생성_시각을_유지하고_수정_시각만_저장한다() {
        User user = persistUser();
        entityManager.persist(new RecommendationPreference(user, CREATED_AT));
        entityManager.flush();
        entityManager.clear();
        LocalDateTime changedAt = CREATED_AT.plusMinutes(10);

        RecommendationPreference preference = entityManager.find(RecommendationPreference.class, user.getId());
        preference.updatePreferences(null, null, null, null,
            List.of(Religion.NONE), List.of(Drinking.NEVER),
            List.of(Smoking.NON_SMOKER), changedAt);
        entityManager.flush();
        entityManager.clear();

        RecommendationPreference saved = entityManager.find(RecommendationPreference.class, user.getId());
        assertThat(saved.getReligion()).containsExactly(Religion.NONE);
        assertThat(saved.getDrinking()).containsExactly(Drinking.NEVER);
        assertThat(saved.getSmoking()).containsExactly(Smoking.NON_SMOKER);
        assertThat(saved.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(saved.getUpdatedAt()).isEqualTo(changedAt);
    }

    @Test
    void 조회한_선택값의_순서만_바꾸면_저장값과_수정_시각을_유지한다() {
        User user = persistUser();
        List<Religion> religions = List.of(Religion.CATHOLIC, Religion.NONE);
        entityManager.persist(new RecommendationPreference(user, religions, List.of(), List.of(),
            CREATED_AT));
        entityManager.flush();
        entityManager.clear();

        RecommendationPreference preference = entityManager.find(RecommendationPreference.class, user.getId());
        preference.updatePreferences(null, null, null, null,
            List.of(Religion.NONE, Religion.CATHOLIC),
            List.of(), List.of(), CREATED_AT.plusMinutes(10));
        assertThatThrownBy(() -> preference.getReligion().add(Religion.OTHER))
            .isInstanceOf(UnsupportedOperationException.class);
        entityManager.flush();
        entityManager.clear();

        RecommendationPreference saved = entityManager.find(RecommendationPreference.class, user.getId());
        assertThat(saved.getReligion()).containsExactlyElementsOf(religions);
        assertThat(saved.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(saved.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void 숫자_경계와_선택값을_함께_저장하고_조회한다() {
        User user = persistUser();
        entityManager.persist(new RecommendationPreference(user,
            (short) 19, (short) 39, (short) 130, (short) 220,
            List.of(Religion.NONE), List.of(Drinking.NEVER),
            List.of(Smoking.NON_SMOKER), CREATED_AT));
        entityManager.flush();
        entityManager.clear();

        RecommendationPreference saved = entityManager.find(RecommendationPreference.class, user.getId());
        assertThat(saved.getMinAge()).isEqualTo((short) 19);
        assertThat(saved.getMaxAge()).isEqualTo((short) 39);
        assertThat(saved.getMinHeight()).isEqualTo((short) 130);
        assertThat(saved.getMaxHeight()).isEqualTo((short) 220);
        assertThat(saved.getReligion()).containsExactly(Religion.NONE);
        assertThat(saved.getDrinking()).containsExactly(Drinking.NEVER);
        assertThat(saved.getSmoking()).containsExactly(Smoking.NON_SMOKER);
    }

    @Test
    void 전체_조건을_초기화하면_숫자_NULL과_빈_배열을_저장한다() {
        User user = persistUser();
        entityManager.persist(new RecommendationPreference(user,
            (short) 25, (short) 32, (short) 160, (short) 180,
            List.of(Religion.NONE), List.of(Drinking.NEVER),
            List.of(Smoking.NON_SMOKER), CREATED_AT));
        entityManager.flush();
        entityManager.clear();
        LocalDateTime resetAt = CREATED_AT.plusMinutes(10);

        RecommendationPreference preference = entityManager.find(RecommendationPreference.class, user.getId());
        preference.updatePreferences(null, null, null, null,
            List.of(), List.of(), List.of(), resetAt);
        entityManager.flush();
        entityManager.clear();

        RecommendationPreference saved = entityManager.find(RecommendationPreference.class, user.getId());
        assertThat(saved.getMinAge()).isNull();
        assertThat(saved.getMaxAge()).isNull();
        assertThat(saved.getMinHeight()).isNull();
        assertThat(saved.getMaxHeight()).isNull();
        assertThat(saved.getReligion()).isEmpty();
        assertThat(saved.getDrinking()).isEmpty();
        assertThat(saved.getSmoking()).isEmpty();
        assertThat(saved.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(saved.getUpdatedAt()).isEqualTo(resetAt);
    }

    private User persistUser() {
        User user = User.create("테스트", LocalDate.of(1996, 1, 1), Gender.MALE,
            LocalDateTime.now());
        user.activate(LocalDateTime.now());
        entityManager.persist(user);
        return user;
    }
}
