package com.team.dating_backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.chat.entity.ChatMessageOutbox;
import com.team.dating_backend.chat.repository.ChatMessageOutboxRepository;
import com.team.dating_backend.matching.entity.Like;
import com.team.dating_backend.matching.repository.LikeRepository;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.enums.JobCategory;
import com.team.dating_backend.recommendation.entity.RecommendationBatch;
import com.team.dating_backend.recommendation.entity.RecommendationItem;
import com.team.dating_backend.recommendation.repository.RecommendationItemRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
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
class FlywayJpaIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private Flyway flyway;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ChatMessageOutboxRepository outboxes;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private RecommendationItemRepository recommendationItemRepository;

    @Test
    void 직업군을_VARCHAR로_저장하고_모든_코드를_enum으로_조회한다() {
        Profile profile = activeProfile("직업검증", JobCategory.OTHER);
        for (JobCategory job : JobCategory.values()) {
            profile.updateProfile(null, profile.getNickname(), null, null, null, job,
                null, null, null, null);
            entityManager.flush();
            assertThat(jdbc.queryForObject("SELECT job FROM profiles WHERE id = ?",
                String.class, profile.getId())).isEqualTo(job.name());
            entityManager.clear();
            profile = entityManager.find(Profile.class, profile.getId());
            assertThat(profile.getJob()).isEqualTo(job);
        }
    }

    @Test
    void 추천과_좋아요의_생성자_조회가_직업_enum을_반환한다() {
        Profile viewer = activeProfile("조회회원", JobCategory.OTHER);
        Profile candidate = activeProfile("추천회원", JobCategory.IT_DATA);
        Long viewerId = viewer.getUser().getId();
        Long candidateId = candidate.getUser().getId();
        RecommendationBatch batch = entityManager.persist(
            new RecommendationBatch(viewerId, LocalDateTime.now()));
        entityManager.persist(new RecommendationItem(batch.getId(), candidateId, 1));
        entityManager.flush();
        assertThat(recommendationItemRepository.findEligibleItems(batch.getId(), viewerId,
            null, null, PageRequest.of(0, 10)))
            .singleElement().satisfies(row -> assertThat(row.job()).isEqualTo(JobCategory.IT_DATA));

        entityManager.persist(new Like(viewerId, candidateId, LocalDateTime.now()));
        entityManager.flush();
        assertThat(likeRepository.findSentPendingLikes(viewerId, null, PageRequest.of(0, 10)))
            .singleElement().satisfies(row -> assertThat(row.job()).isEqualTo(JobCategory.IT_DATA));
        assertThat(likeRepository.findReceivedPendingLikes(candidateId, null, PageRequest.of(0, 10)))
            .singleElement().satisfies(row -> assertThat(row.job()).isEqualTo(JobCategory.OTHER));
    }

    private Profile activeProfile(String nickname, JobCategory job) {
        User user = User.create(nickname, LocalDate.of(2000, 1, 1), Gender.FEMALE, LocalDateTime.now());
        user.activate(LocalDateTime.now());
        entityManager.persist(user);
        Profile profile = new Profile(user);
        profile.updateProfile(null, nickname, null, null, null, job, null, null, null, null);
        return entityManager.persistAndFlush(profile);
    }

    @Test
    void Flyway가_생성한_스키마를_전체_JPA_엔티티가_검증한다() {
        assertThat(flyway.info().applied()).isNotEmpty();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void Green이_오래된_엔티티를_저장해도_Blue의_최신_발행상태를_덮어쓰지_않는다() {
        ChatMessageOutbox outbox = outboxes.saveAndFlush(
            new ChatMessageOutbox(901L, LocalDateTime.now()));
        jdbc.update("""
            UPDATE chat_message_outbox SET published_at=NOW(6), failure_count=3,
                last_failure_type='BLUE_RETRY', next_attempt_at='2099-01-01 00:00:00'
            WHERE id=?
            """, outbox.getId());

        outbox.markRabbitPublished(LocalDateTime.now());
        outboxes.saveAndFlush(outbox);

        assertThat(jdbc.queryForObject(
            "SELECT failure_count FROM chat_message_outbox WHERE id=?", Integer.class,
            outbox.getId())).isEqualTo(3);
        assertThat(jdbc.queryForObject(
            "SELECT published_at FROM chat_message_outbox WHERE id=?", LocalDateTime.class,
            outbox.getId())).isNotNull();
        assertThat(jdbc.queryForObject(
            "SELECT last_failure_type FROM chat_message_outbox WHERE id=?", String.class,
            outbox.getId())).isEqualTo("BLUE_RETRY");
        assertThat(jdbc.queryForObject(
            "SELECT rabbit_published_at FROM chat_message_outbox WHERE id=?", LocalDateTime.class,
            outbox.getId())).isNotNull();
    }

    @Test
    void Blue가_발행하거나_실패한_메시지도_RabbitMQ는_독립적으로_조회한다() {
        jdbc.update("""
            INSERT INTO chat_message_outbox
                (chat_message_id, created_at, next_attempt_at, failure_count, published_at, failed_at,
                 rabbit_published_at, rabbit_failed_at)
            VALUES
                (902, NOW(6), '2099-01-01', 10, NOW(6), NOW(6), NULL, NULL),
                (903, NOW(6), NOW(6), 0, NULL, NULL, NOW(6), NULL),
                (904, NOW(6), NOW(6), 0, NULL, NULL, NULL, NOW(6))
            """);
        entityManager.clear();

        assertThat(outboxes
            .findByRabbitPublishedAtIsNullAndRabbitFailedAtIsNullAndRabbitNextAttemptAtLessThanEqualOrderByRabbitNextAttemptAtAscIdAsc(
                LocalDateTime.now().plusSeconds(1), PageRequest.of(0, 100)))
            .extracting(ChatMessageOutbox::getChatMessageId).containsExactly(902L);
    }
}
