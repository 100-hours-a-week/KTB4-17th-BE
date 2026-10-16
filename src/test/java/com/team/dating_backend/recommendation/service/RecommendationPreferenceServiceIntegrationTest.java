package com.team.dating_backend.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.recommendation.dto.request.RecommendationPreferenceSaveRequest;
import com.team.dating_backend.recommendation.dto.response.RecommendationPreferenceGetResponse;
import com.team.dating_backend.recommendation.dto.response.RecommendationPreferenceResponse;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.recommendation.entity.RecommendationPreference;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.recommendation.repository.RecommendationPreferenceRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
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
@Import({RecommendationPreferenceService.class, RecommendationBatchCreateService.class})
@Testcontainers
class RecommendationPreferenceServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private RecommendationPreferenceService service;

    @Autowired
    private RecommendationPreferenceRepository recommendationPreferenceRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.now();
        User user = User.create("테스트", LocalDate.of(1996, 1, 1), Gender.MALE, now);
        user.activate(now);
        entityManager.persist(user);
        entityManager.flush();
        userId = user.getId();
        entityManager.clear();
    }

    @Test
    void 선호_설정_페이지를_조회하기만_하면_DB_행이_생성되지_않는다() {
        RecommendationPreferenceGetResponse response = service.getPreferences(userId);
        flushAndClear();

        assertThat(response.preference()).isEqualTo(RecommendationPreferenceResponse.unrestricted());
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from recommendation_preferences where user_id = ?", Long.class,
            userId)).isZero();
    }

    @Test
    void 저장된_조건을_조회해도_생성_시각과_수정_시각은_바뀌지_않는다() {
        service.savePreferences(userId, new RecommendationPreferenceSaveRequest(
            25, 30, 160, 180, List.of(Religion.NONE, Religion.CATHOLIC), List.of(), List.of()));
        flushAndClear();
        RecommendationPreference stored = recommendationPreferenceRepository.findById(userId).orElseThrow();
        LocalDateTime createdAt = stored.getCreatedAt();
        LocalDateTime updatedAt = stored.getUpdatedAt();
        entityManager.clear();

        RecommendationPreferenceGetResponse response = service.getPreferences(userId);
        flushAndClear();

        assertThat(response.preference().minAge()).isEqualTo((short) 25);
        assertThat(response.preference().maxAge()).isEqualTo((short) 30);
        assertThat(response.preference().minHeight()).isEqualTo((short) 160);
        assertThat(response.preference().maxHeight()).isEqualTo((short) 180);
        assertThat(response.preference().religion())
            .containsExactly(Religion.NONE, Religion.CATHOLIC);
        RecommendationPreference afterRead = recommendationPreferenceRepository.findById(userId).orElseThrow();
        assertThat(afterRead.getCreatedAt()).isEqualTo(createdAt);
        assertThat(afterRead.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void 최초_초기화_요청도_회원_PK와_빈_JSON_배열로_저장한다() {
        service.savePreferences(userId,
            new RecommendationPreferenceSaveRequest(null, null, null, null, null, null, null));
        flushAndClear();

        RecommendationPreference preference = recommendationPreferenceRepository.findById(userId).orElseThrow();
        assertThat(preference.getUserId()).isEqualTo(userId);
        assertThat(preference.getMinAge()).isNull();
        assertThat(preference.getMaxAge()).isNull();
        assertThat(preference.getMinHeight()).isNull();
        assertThat(preference.getMaxHeight()).isNull();
        assertThat(preference.getUpdatedAt()).isEqualTo(preference.getCreatedAt());
        assertEmptyJsonArrays();
    }

    @Test
    void 기존_행은_변경_감지로_수정하고_같은_조건에는_수정_시각을_유지한다() {
        service.savePreferences(userId, new RecommendationPreferenceSaveRequest(
            25, 30, 160, 180, List.of(Religion.NONE, Religion.CATHOLIC), List.of(), List.of()));
        flushAndClear();
        RecommendationPreference initial = recommendationPreferenceRepository.findById(userId).orElseThrow();
        LocalDateTime createdAt = initial.getCreatedAt();
        LocalDateTime updatedAt = initial.getUpdatedAt();

        service.savePreferences(userId, new RecommendationPreferenceSaveRequest(
            25, 30, 160, 180, List.of(Religion.CATHOLIC, Religion.NONE), List.of(), List.of()));
        flushAndClear();
        RecommendationPreference unchanged = recommendationPreferenceRepository.findById(userId).orElseThrow();
        assertThat(unchanged.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(unchanged.getMinAge()).isEqualTo((short) 25);
        assertThat(unchanged.getMaxAge()).isEqualTo((short) 30);
        assertThat(unchanged.getMinHeight()).isEqualTo((short) 160);
        assertThat(unchanged.getMaxHeight()).isEqualTo((short) 180);

        service.savePreferences(userId,
            new RecommendationPreferenceSaveRequest(null, null, null, null, null, null, null));
        flushAndClear();
        RecommendationPreference reset = recommendationPreferenceRepository.findById(userId).orElseThrow();
        assertThat(reset.getCreatedAt()).isEqualTo(createdAt);
        assertThat(reset.getUpdatedAt()).isAfter(updatedAt);
        assertThat(reset.getMinAge()).isNull();
        assertThat(reset.getMaxAge()).isNull();
        assertThat(reset.getMinHeight()).isNull();
        assertThat(reset.getMaxHeight()).isNull();
        assertEmptyJsonArrays();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from recommendation_preferences where user_id = ?", Long.class,
            userId)).isEqualTo(1L);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private void assertEmptyJsonArrays() {
        for (String column : List.of("religion", "drinking", "smoking")) {
            assertThat(jdbcTemplate.queryForObject(
                "select JSON_LENGTH(" + column + ") from recommendation_preferences where user_id = ?",
                Integer.class, userId)).isZero();
        }
    }
}
