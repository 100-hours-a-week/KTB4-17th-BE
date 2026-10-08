package com.team.dating_backend.recommendation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.matching.entity.Like;
import com.team.dating_backend.matching.enums.LikeStatus;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import com.team.dating_backend.recommendation.entity.RecommendationBatch;
import com.team.dating_backend.recommendation.entity.RecommendationItem;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.entity.UserBlock;
import com.team.dating_backend.user.enums.Gender;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(
    properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.flyway.enabled=false"
    }
)
@Testcontainers(disabledWithoutDocker = true)
class RecommendationProfileAccessIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private RecommendationItemRepository repository;

    @Autowired
    private RecommendationCandidateRepository candidateRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User requester;
    private User candidate;
    private RecommendationBatch batch;
    private Profile candidateProfile;

    @BeforeEach
    void setUp() {
        requester = activeUser();
        candidate = activeUser(Gender.FEMALE, LocalDate.of(1996, 1, 1));
        candidateProfile = profile(candidate, (short) 170, Religion.NONE, Drinking.NEVER,
            Smoking.NON_SMOKER);
        batch = new RecommendationBatch(requester.getId(), LocalDateTime.now());
        entityManager.persist(batch);
        entityManager.persist(new RecommendationItem(batch.getId(), candidate.getId(), 1));
        entityManager.flush();
    }

    @Test
    void 활성_추천_배치의_적격_상대는_상세_조회_권한이_있다() {
        assertThat(hasAccess()).isTrue();
    }

    @Test
    void 동일_배치에_같은_추천_순위를_중복_저장할_수_없다() {
        User otherCandidate = activeUser();

        assertThatThrownBy(() -> {
            entityManager.persist(
                new RecommendationItem(batch.getId(), otherCandidate.getId(), 1));
            entityManager.flush();
        })
            .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void 동일_배치에_같은_추천_후보를_중복_저장할_수_없다() {
        assertThatThrownBy(() -> {
            entityManager.persist(new RecommendationItem(batch.getId(), candidate.getId(), 2));
            entityManager.flush();
        })
            .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void 다른_사용자의_추천_배치는_조회_권한을_부여하지_않는다() {
        User otherViewer = activeUser();
        entityManager.flush();

        assertThat(repository.existsEligibleCandidateInActiveBatch(
            otherViewer.getId(), candidate.getId())).isFalse();
    }

    @Test
    void 추천_배치에_없는_회원과_본인은_조회_권한이_없다() {
        User otherMember = activeUser();
        entityManager.persist(new Profile(otherMember));
        entityManager.persist(new RecommendationItem(batch.getId(), requester.getId(), 2));
        entityManager.persist(new Profile(requester));
        entityManager.flush();

        assertThat(repository.existsEligibleCandidateInActiveBatch(
            requester.getId(), otherMember.getId())).isFalse();
        assertThat(repository.existsEligibleCandidateInActiveBatch(
            requester.getId(), requester.getId())).isFalse();
    }

    @Test
    void 교체된_이전_배치의_추천_상대는_조회_권한이_없다() {
        jdbcTemplate.update("update recommendation_batches set deleted_at = ? where id = ?",
            LocalDateTime.now(), batch.getId());
        entityManager.persist(new RecommendationBatch(requester.getId(), LocalDateTime.now()));
        entityManager.flush();

        assertThat(hasAccess()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ONBOARDING", "SUSPENDED", "WITHDRAWN"})
    void 비활성_추천_상대는_조회_권한이_없다(String status) {
        jdbcTemplate.update("update users set status = ? where id = ?", status, candidate.getId());

        assertThat(hasAccess()).isFalse();
    }

    @Test
    void 비활성_조회자는_추천을_통한_조회_권한이_없다() {
        jdbcTemplate.update("update users set status = 'SUSPENDED' where id = ?", requester.getId());

        assertThat(hasAccess()).isFalse();
    }

    @Test
    void 삭제된_프로필은_조회_권한이_없다() {
        jdbcTemplate.update("update profiles set deleted_at = ? where user_id = ?",
            LocalDateTime.now(), candidate.getId());

        assertThat(hasAccess()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 양방향_차단_관계는_추천_조회_권한이_없다(boolean outgoing) {
        entityManager.persist(new UserBlock(
            outgoing ? requester.getId() : candidate.getId(),
            outgoing ? candidate.getId() : requester.getId(), LocalDateTime.now()));
        entityManager.flush();

        assertThat(hasAccess()).isFalse();
    }

    @Test
    void 패스한_상대는_추천_조회_권한이_없다() {
        jdbcTemplate.update("""
            insert into recommendation_passes (passer_user_id, passed_user_id, created_at)
            values (?, ?, ?)
            """, requester.getId(), candidate.getId(), LocalDateTime.now());

        assertThat(hasAccess()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = LikeStatus.class, names = {"PENDING", "REJECTED"})
    void 보낸_좋아요나_거절_관계는_추천_경로에서_제외한다(LikeStatus status) {
        Like like = new Like(requester.getId(), candidate.getId(), LocalDateTime.now());
        if (status == LikeStatus.REJECTED) {
            like.resolveLike(status, LocalDateTime.now());
        }
        entityManager.persist(like);
        entityManager.flush();

        assertThat(hasAccess()).isFalse();
    }

    @Test
    void 미설정_조건은_ACTIVE인_이성_후보만_선정한다() {
        User sameGender = activeUser();
        profile(sameGender, (short) 170, Religion.NONE, Drinking.NEVER, Smoking.NON_SMOKER);
        User withdrawn = activeUser(Gender.FEMALE, LocalDate.of(1996, 1, 1));
        profile(withdrawn, (short) 170, Religion.NONE, Drinking.NEVER, Smoking.NON_SMOKER);
        entityManager.flush();
        jdbcTemplate.update("update users set status = 'WITHDRAWN' where id = ?", withdrawn.getId());

        assertThat(candidates(null, null, null, null, List.of(), List.of(), List.of()))
            .containsExactly(candidate.getId());
    }

    @ParameterizedTest
    @CsvSource(
        {
            "1995-10-08, false", "1995-10-09, true", "1996-10-08, true",
            "2001-10-08, true", "2001-10-09, false"
        }
    )
    void 만_나이_25세부터_30세까지_생일_경계를_포함한다(String birthDate, boolean expected) {
        entityManager.flush();
        jdbcTemplate.update("update users set birth_date = ? where id = ?", birthDate, candidate.getId());

        assertThat(candidates(LocalDate.of(1995, 10, 8), LocalDate.of(2001, 10, 8),
            null, null, List.of(), List.of(), List.of()).contains(candidate.getId()))
            .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(
        {
            "2025-02-28, 2000-02-29, 24, true",
            "2025-03-01, 2000-02-29, 24, false",
            "2024-02-29, 2000-02-29, 24, true",
            "2024-02-28, 2000-02-29, 23, true"
        }
    )
    void 윤년_생일에도_같은_만_나이_범위를_적용한다(
        String todayText, String birthDate, int age, boolean expected) {
        entityManager.flush();
        jdbcTemplate.update("update users set birth_date = ? where id = ?", birthDate, candidate.getId());
        LocalDate today = LocalDate.parse(todayText);

        assertThat(candidates(today.minusYears(age + 1L), today.minusYears(age),
            null, null, List.of(), List.of(), List.of()).contains(candidate.getId()))
            .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"159, false", "160, true", "180, true", "181, false"})
    void 키_범위의_최소와_최대를_포함한다(short height, boolean expected) {
        candidateProfile.updateProfile(null, "추천상대", height, null, null, null,
            Religion.NONE, null, Drinking.NEVER, Smoking.NON_SMOKER);

        assertThat(candidates(null, null, (short) 160, (short) 180,
            List.of(), List.of(), List.of()).contains(candidate.getId())).isEqualTo(expected);
    }

    @Test
    void 최소나이와_최대키만_설정해도_한쪽_경계를_적용한다() {
        assertThat(candidates(null, LocalDate.of(2001, 10, 8), null, (short) 170,
            List.of(), List.of(), List.of())).containsExactly(candidate.getId());
        assertThat(candidates(null, LocalDate.of(1990, 10, 8), null, null,
            List.of(), List.of(), List.of())).isEmpty();
        assertThat(candidates(null, null, null, (short) 169,
            List.of(), List.of(), List.of())).isEmpty();
    }

    @Test
    void 항목은_AND로_결합하고_각_선택_항목은_OR로_비교한다() {
        User selectedAlternative = activeUser(Gender.FEMALE, LocalDate.of(1996, 1, 1));
        profile(selectedAlternative, (short) 170, Religion.CATHOLIC, Drinking.SOCIAL, Smoking.OCCASIONAL);
        User wrongReligion = activeUser(Gender.FEMALE, LocalDate.of(1996, 1, 1));
        profile(wrongReligion, (short) 170, Religion.OTHER, Drinking.NEVER, Smoking.NON_SMOKER);
        User wrongDrinking = activeUser(Gender.FEMALE, LocalDate.of(1996, 1, 1));
        profile(wrongDrinking, (short) 170, Religion.NONE, Drinking.FREQUENT, Smoking.NON_SMOKER);
        User wrongSmoking = activeUser(Gender.FEMALE, LocalDate.of(1996, 1, 1));
        profile(wrongSmoking, (short) 170, Religion.NONE, Drinking.NEVER, Smoking.FREQUENT);

        assertThat(candidates(null, null, (short) 160, (short) 180,
            List.of(Religion.NONE, Religion.CATHOLIC), List.of(Drinking.NEVER, Drinking.SOCIAL),
            List.of(Smoking.NON_SMOKER, Smoking.OCCASIONAL)))
            .containsExactly(candidate.getId(), selectedAlternative.getId());
    }

    @Test
    void 미설정_선택항목은_값이_없는_프로필도_제한하지_않는다() {
        candidateProfile.updateProfile(null, "추천상대", null, null, null, null,
            null, null, null, null);

        assertThat(candidates(null, null, null, null, List.of(), List.of(), List.of()))
            .containsExactly(candidate.getId());
        assertThat(candidates(null, null, null, null, List.of(Religion.NONE), List.of(), List.of()))
            .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 차단_해제_이력이_있어도_양방향_추천_후보에서_제외한다(boolean outgoing) {
        UserBlock block = new UserBlock(
            outgoing ? requester.getId() : candidate.getId(),
            outgoing ? candidate.getId() : requester.getId(), LocalDateTime.now());
        entityManager.persist(block);
        entityManager.flush();
        jdbcTemplate.update("update user_blocks set unblocked_at = now(6) where id = ?", block.getId());

        assertThat(candidates(null, null, null, null, List.of(), List.of(), List.of())).isEmpty();
        assertThat(hasAccess()).isFalse();
    }

    private List<Long> candidates(LocalDate birthDateAfter, LocalDate birthDateOnOrBefore,
        Short minHeight, Short maxHeight, List<Religion> religion,
        List<Drinking> drinking, List<Smoking> smoking) {
        return candidateRepository.findEligibleCandidateIds(
            requester.getId(), Gender.FEMALE, birthDateAfter, birthDateOnOrBefore, minHeight, maxHeight,
            religion.isEmpty(), religion.isEmpty() ? List.of(Religion.values()) : religion,
            drinking.isEmpty(), drinking.isEmpty() ? List.of(Drinking.values()) : drinking,
            smoking.isEmpty(), smoking.isEmpty() ? List.of(Smoking.values()) : smoking);
    }

    private Profile profile(User user, Short height, Religion religion, Drinking drinking, Smoking smoking) {
        Profile profile = new Profile(user);
        profile.updateProfile(null, "상대" + user.getId(), height, null, null, null,
            religion, null, drinking, smoking);
        entityManager.persist(profile);
        return profile;
    }

    private boolean hasAccess() {
        return repository.existsEligibleCandidateInActiveBatch(requester.getId(), candidate.getId());
    }

    private User activeUser() {
        return activeUser(Gender.MALE, LocalDate.of(1996, 1, 1));
    }

    private User activeUser(Gender gender, LocalDate birthDate) {
        User user = User.create("테스트", birthDate, gender, LocalDateTime.now());
        user.activate(LocalDateTime.now());
        entityManager.persist(user);
        return user;
    }
}
