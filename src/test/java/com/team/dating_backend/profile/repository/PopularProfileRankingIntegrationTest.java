package com.team.dating_backend.profile.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.matching.entity.Like;
import com.team.dating_backend.matching.enums.LikeStatus;
import com.team.dating_backend.matching.repository.LikeRepository;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@Testcontainers
class PopularProfileRankingIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private LikeRepository likes;
    @Autowired
    private UserRepository users;
    @Autowired
    private ProfileRepository profiles;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 인증된_활성회원만_서로_다른_발신자_수와_아이디_순으로_상위_10명을_조회한다() {
        List<User> candidates = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            candidates.add(user(true, true));
        }
        User firstSender = user(false, false);
        User secondSender = user(false, false);
        for (User candidate : candidates) {
            likes.save(new Like(firstSender.getId(), candidate.getId(), NOW));
        }
        Like rejected = new Like(secondSender.getId(), candidates.get(5).getId(), NOW);
        rejected.resolveLike(LikeStatus.REJECTED, NOW);
        likes.save(rejected);
        likes.save(new Like(secondSender.getId(), candidates.get(5).getId(), NOW));

        User unverified = user(true, false);
        User onboarding = user(false, true);
        User suspended = user(true, true);
        User withdrawn = user(true, true);
        User deletedProfile = user(true, true);
        profiles.findByUserIdAndDeletedAtIsNull(deletedProfile.getId()).orElseThrow().markDeleted(NOW);
        for (User excluded : List.of(unverified, onboarding, suspended, withdrawn, deletedProfile)) {
            likes.save(new Like(firstSender.getId(), excluded.getId(), NOW));
            likes.save(new Like(secondSender.getId(), excluded.getId(), NOW));
        }
        likes.flush();
        profiles.flush();
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", suspended.getId());
        jdbc.update("UPDATE users SET status = 'WITHDRAWN' WHERE id = ?", withdrawn.getId());

        List<PopularProfileRow> ranking = likes.findPopularProfiles(PageRequest.of(0, 10));

        assertThat(ranking).hasSize(10);
        assertThat(ranking.getFirst().userId()).isEqualTo(candidates.get(5).getId());
        assertThat(ranking.getFirst().likeCount()).isEqualTo(2);
        assertThat(ranking.subList(1, 10)).extracting(PopularProfileRow::userId)
            .containsExactly(candidates.get(0).getId(), candidates.get(1).getId(),
                candidates.get(2).getId(), candidates.get(3).getId(), candidates.get(4).getId(),
                candidates.get(6).getId(), candidates.get(7).getId(), candidates.get(8).getId(),
                candidates.get(9).getId());
    }

    @Test
    void 좋아요가_없으면_빈_랭킹을_반환한다() {
        user(true, true);
        assertThat(likes.findPopularProfiles(PageRequest.of(0, 10))).isEmpty();
    }

    private User user(boolean active, boolean verified) {
        User user = User.create("회원", LocalDate.of(2000, 1, 1), Gender.MALE, NOW);
        if (active)
            user.activate(NOW);
        user.updateFaceVerificationStatus(verified, NOW);
        users.saveAndFlush(user);
        profiles.saveAndFlush(new Profile(user));
        return user;
    }
}
