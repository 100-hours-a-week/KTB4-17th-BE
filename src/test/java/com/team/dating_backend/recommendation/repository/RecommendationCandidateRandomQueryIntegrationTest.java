package com.team.dating_backend.recommendation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.entity.UserBlock;
import com.team.dating_backend.user.enums.Gender;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.util.ReflectionTestUtils;
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
class RecommendationCandidateRandomQueryIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private RecommendationCandidateRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 추천_랜덤_순서에서_커서_이후의_적합_후보를_조회한다() {
        User requester = activeUser(Gender.MALE, 900L);
        activeCandidate(Gender.FEMALE, 100L);
        User cursorCandidate = activeCandidate(Gender.FEMALE, 200L);
        User sameKeyCandidate = activeCandidate(Gender.FEMALE, 200L);
        activeCandidate(Gender.MALE, 250L);
        User laterCandidate = activeCandidate(Gender.FEMALE, 300L);
        activeCandidate(Gender.FEMALE, 350L, (short) 150);
        User blockedCandidate = activeCandidate(Gender.FEMALE, 400L);
        entityManager.persist(new UserBlock(
            requester.getId(), blockedCandidate.getId(), LocalDateTime.now()));
        entityManager.flush();

        assertThat(positions(requester.getId(), 200L, cursorCandidate.getId(), 300))
            .containsExactly(
                new RecommendationCandidatePosition(sameKeyCandidate.getId(), 200L),
                new RecommendationCandidatePosition(laterCandidate.getId(), 300L));
    }

    @Test
    void 요청한_개수가_커도_후보는_최대_300명만_조회한다() {
        User requester = activeUser(Gender.MALE, 1_000L);
        for (long randomKey = 1; randomKey <= 305; randomKey++) {
            activeCandidate(Gender.FEMALE, randomKey);
        }
        entityManager.flush();

        List<RecommendationCandidatePosition> positions = positions(
            requester.getId(), 0L, 0L, 500);

        assertThat(positions)
            .hasSize(300)
            .extracting(RecommendationCandidatePosition::randomKey)
            .containsExactlyElementsOf(
                java.util.stream.LongStream.rangeClosed(1, 300).boxed().toList());
    }

    private List<RecommendationCandidatePosition> positions(
        Long requesterUserId, Long afterRandomKey, Long afterUserId, int limit) {
        return repository.findEligibleCandidatePositionsByGenders(
            requesterUserId, List.of(Gender.FEMALE),
            null, null, (short) 160, null,
            true, List.of(Religion.values()),
            true, List.of(Drinking.values()),
            true, List.of(Smoking.values()),
            afterRandomKey, afterUserId, limit);
    }

    private User activeCandidate(Gender gender, long randomKey) {
        return activeCandidate(gender, randomKey, (short) 170);
    }

    private User activeCandidate(Gender gender, long randomKey, short height) {
        User user = activeUser(gender, randomKey);
        Profile profile = new Profile(user);
        profile.updateProfile(null, "후보" + randomKey, height, null, null, null,
            null, null, null, null);
        entityManager.persist(profile);
        return user;
    }

    private User activeUser(Gender gender, long randomKey) {
        User user = User.create(
            "회원", LocalDate.of(1996, 1, 1), gender, LocalDateTime.now());
        user.activate(LocalDateTime.now());
        ReflectionTestUtils.setField(user, "recommendationRandomKey", randomKey);
        entityManager.persist(user);
        return user;
    }
}
