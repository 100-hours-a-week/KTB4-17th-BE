package com.team.dating_backend.aisimulation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.aisimulation.dto.event.AiSimulationRequestedEvent;
import com.team.dating_backend.aisimulation.entity.AiSimulationSession;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import com.team.dating_backend.aisimulation.kafka.AiSimulationRecommendationConsumer;
import com.team.dating_backend.aisimulation.kafka.AiSimulationFailureClassifier;
import com.team.dating_backend.aisimulation.repository.AiSimulationSessionRepository;
import com.team.dating_backend.recommendation.entity.RecommendationItem;
import com.team.dating_backend.recommendation.enums.SimulationRecommendationResult;
import com.team.dating_backend.recommendation.repository.RecommendationBatchRepository;
import com.team.dating_backend.recommendation.repository.RecommendationItemRepository;
import com.team.dating_backend.recommendation.service.RecommendationBatchCreateService;
import com.team.dating_backend.recommendation.service.SimulationRecommendationService;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(
    showSql = false, properties = {
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"
    }
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    {AiSimulationRecommendationConsumer.class, AiSimulationRecommendationService.class,
        SimulationRecommendationService.class, RecommendationBatchCreateService.class,
        AiSimulationFailureClassifier.class}
)
@Testcontainers
@Timeout(30)
class AiSimulationRecommendationIntegrationTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 11, 7, 10, 5);

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private AiSimulationRecommendationConsumer consumer;
    @Autowired
    private AiSimulationRecommendationService service;
    @Autowired
    private RecommendationBatchCreateService batchService;
    @Autowired
    private RecommendationBatchRepository batches;
    @Autowired
    private RecommendationItemRepository items;
    @Autowired
    private AiSimulationSessionRepository sessions;
    @Autowired
    private UserRepository users;
    @Autowired
    private JdbcTemplate jdbc;

    private Long requester;
    private Long target;
    private Long sessionId;

    @BeforeEach
    void setUp() {
        for (String table : List.of("recommendation_passes", "user_blocks", "likes", "matches",
            "recommendation_items", "recommendation_batches", "profiles", "ai_simulation_sessions", "users")) {
            jdbc.update("DELETE FROM " + table);
        }
        requester = user("요청자");
        target = user("상대방");
        jdbc.update("""
            INSERT INTO profiles (user_id, nickname, created_at, updated_at)
            VALUES (?, '요청자', NOW(6), NOW(6))
            """, requester);
        sessionId = sessions.saveAndFlush(new AiSimulationSession(requester, target, CREATED_AT)).getId();
    }

    @Test
    void 요청_이벤트는_상대방의_목록에_요청자를_추가하고_재처리와_갱신에도_유지한다() {
        consume(sessionId);
        var original = targetItems().getFirst();
        consume(sessionId);
        assertThat(service.addRequester(sessionId)).isEqualTo(SimulationRecommendationResult.ALREADY_APPLIED);
        batchService.refreshRecommendationBatch(target);

        assertThat(targetItems()).singleElement().satisfies(item -> {
            assertThat(item.getId()).isEqualTo(original.getId());
            assertThat(item.getCandidateUserId()).isEqualTo(requester);
            assertThat(item.getRankingOrder()).isEqualTo(original.getRankingOrder());
            assertThat(item.isSimulationRequested()).isTrue();
        });
        assertThat(batches.findByUserIdAndDeletedAtIsNull(requester)).isEmpty();
    }

    @Test
    void 기존_일반_추천은_항목과_순위를_유지하면서_시뮬레이션_출처만_추가한다() {
        long batchId = batchService.createRecommendationBatch(target).orElseThrow().batchId();
        var original = items.saveAndFlush(new RecommendationItem(batchId, requester, 7));

        assertThat(service.addRequester(sessionId)).isEqualTo(SimulationRecommendationResult.APPLIED);
        consume(sessionId);
        batchService.refreshRecommendationBatch(target);

        assertThat(targetItems()).singleElement().satisfies(item -> {
            assertThat(item.getId()).isEqualTo(original.getId());
            assertThat(item.getRankingOrder()).isEqualTo(7);
            assertThat(item.isSimulationRequested()).isTrue();
        });
    }

    @Test
    void 동시_재처리는_한_번만_반영하고_나머지는_이미_반영된_결과를_반환한다() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.addRequester(sessionId);
            });
            var second = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.addRequester(sessionId);
            });
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(SimulationRecommendationResult.APPLIED,
                    SimulationRecommendationResult.ALREADY_APPLIED);
        }
        assertThat(targetItems()).singleElement().satisfies(item -> assertThat(item.isSimulationRequested()).isTrue());
        assertThat(batches.count()).isEqualTo(1);
    }

    @Test
    void 추천_갱신과_요청_이벤트가_겹쳐도_시뮬레이션_추천을_유지한다() throws Exception {
        batchService.createRecommendationBatch(target);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var refresh = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return batchService.refreshRecommendationBatch(target);
            });
            var requested = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                consume(sessionId);
                return true;
            });
            refresh.get(15, TimeUnit.SECONDS);
            requested.get(15, TimeUnit.SECONDS);
        }
        batchService.refreshRecommendationBatch(target);
        assertThat(targetItems()).singleElement().satisfies(item -> assertThat(item.isSimulationRequested()).isTrue());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"pass", "block", "ownerWithdrawn", "candidateWithdrawn", "ownerInactive",
            "candidateInactive", "rejected", "pending", "matched", "missingProfile"}
    )
    void 기존_추천_제외_정책에_해당하면_저장하지_않는다(String policy) {
        switch (policy) {
            case "pass" -> jdbc.update("""
                INSERT INTO recommendation_passes (passer_user_id, passed_user_id, created_at)
                VALUES (?, ?, NOW(6))
                """, target, requester);
            case "block" -> jdbc.update("""
                INSERT INTO user_blocks (blocker_user_id, blocked_user_id, blocked_at)
                VALUES (?, ?, NOW(6))
                """, requester, target);
            case "ownerWithdrawn" -> jdbc.update("UPDATE users SET status='WITHDRAWN' WHERE id=?", target);
            case "candidateWithdrawn" -> jdbc.update("UPDATE users SET status='WITHDRAWN' WHERE id=?", requester);
            case "ownerInactive" -> jdbc.update("UPDATE users SET status='ONBOARDING' WHERE id=?", target);
            case "candidateInactive" -> jdbc.update("UPDATE users SET status='ONBOARDING' WHERE id=?", requester);
            case "rejected", "pending" -> jdbc.update("""
                INSERT INTO likes (sender_id, receiver_id, status, created_at) VALUES (?, ?, ?, NOW(6))
                """, target, requester, policy.equals("rejected") ? "REJECTED" : "PENDING");
            case "matched" -> jdbc.update("""
                INSERT INTO matches (sender_id, receiver_id, status, matched_at) VALUES (?, ?, 'ENDED', NOW(6))
                """, requester, target);
            case "missingProfile" -> jdbc.update("DELETE FROM profiles WHERE user_id=?", requester);
            default -> throw new IllegalArgumentException(policy);
        }

        consume(sessionId);
        assertThat(service.addRequester(sessionId)).isEqualTo(SimulationRecommendationResult.SKIPPED);
        assertThat(batches.count()).isZero();
        assertThat(items.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_passes", Long.class))
            .isEqualTo(policy.equals("pass") ? 1L : 0L);
    }

    @ParameterizedTest
    @EnumSource(AiSimulationSessionStatus.class)
    void 생성_처리_상태와_관계없이_최초_요청의_추천을_처리한다(AiSimulationSessionStatus status) {
        jdbc.update("UPDATE ai_simulation_sessions SET status=? WHERE id=?", status.name(), sessionId);

        consume(sessionId);

        assertThat(targetItems()).singleElement().satisfies(item -> assertThat(item.isSimulationRequested()).isTrue());
        assertThat(sessions.findById(sessionId).orElseThrow().getStatus()).isEqualTo(status);
    }

    @Test
    void DB_저장_실패는_Consumer_밖으로_전달하고_추천_전체를_롤백한다() {
        jdbc.execute("""
            ALTER TABLE recommendation_items ADD CONSTRAINT test_reject_simulation_recommendation
            CHECK (simulation_requested = FALSE)
            """);
        try {
            assertThatThrownBy(() -> consume(sessionId)).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(batches.count()).isZero();
            assertThat(items.count()).isZero();
            assertThat(sessions.findById(sessionId).orElseThrow().getStatus())
                .isEqualTo(AiSimulationSessionStatus.IN_PROGRESS);
        } finally {
            jdbc.execute("ALTER TABLE recommendation_items DROP CHECK test_reject_simulation_recommendation");
        }
        consume(sessionId);
        assertThat(targetItems()).hasSize(1);
    }

    @Test
    void 세션이_없거나_상대방_ID가_없으면_에러코드를_전달한다() {
        assertThatThrownBy(() -> consume(Long.MAX_VALUE))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.SIMULATION_NOT_FOUND));
        jdbc.update("UPDATE ai_simulation_sessions SET target_user_id=NULL WHERE id=?", sessionId);

        assertThatThrownBy(() -> consume(sessionId))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.SIMULATION_TARGET_USER_MISSING));
        assertThat(batches.count()).isZero();
    }

    private List<RecommendationItem> targetItems() {
        var batch = batches.findByUserIdAndDeletedAtIsNull(target).orElseThrow();
        return items.findAllByRecommendationBatchId(batch.getId());
    }

    private Long user(String name) {
        var user = User.create(name, LocalDate.of(2000, 1, 1), Gender.MALE, CREATED_AT);
        user.activate(CREATED_AT);
        return users.saveAndFlush(user).getId();
    }

    private void consume(Long simulationId) {
        var event = new AiSimulationEventFactory().requested(simulationId, CREATED_AT);
        consumer.consume(new ConsumerRecord<Long, AiSimulationRequestedEvent>(
            "test-requested", 2, 42L, simulationId, event));
    }
}
