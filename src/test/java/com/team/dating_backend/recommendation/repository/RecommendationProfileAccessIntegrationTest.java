package com.team.dating_backend.recommendation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.matching.entity.Like;
import com.team.dating_backend.matching.enums.LikeStatus;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.recommendation.entity.RecommendationBatch;
import com.team.dating_backend.recommendation.entity.RecommendationItem;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.entity.UserBlock;
import com.team.dating_backend.user.enums.Gender;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.hibernate.exception.ConstraintViolationException;
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
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User requester;
    private User candidate;
    private RecommendationBatch batch;

    @BeforeEach
    void setUp() {
        requester = activeUser();
        candidate = activeUser();
        entityManager.persist(new Profile(candidate));
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

    private boolean hasAccess() {
        return repository.existsEligibleCandidateInActiveBatch(requester.getId(), candidate.getId());
    }

    private User activeUser() {
        User user = User.create("테스트", LocalDate.of(1996, 1, 1), Gender.MALE, LocalDateTime.now());
        user.activate(LocalDateTime.now());
        entityManager.persist(user);
        return user;
    }
}
