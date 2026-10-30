package com.team.dating_backend.recommendation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

import com.team.dating_backend.profile.service.ProfileImageGetService;
import com.team.dating_backend.common.lock.repository.PublisherLockRepository;
import com.team.dating_backend.recommendation.dto.request.RecommendationPreferenceSaveRequest;
import com.team.dating_backend.recommendation.entity.RecommendationItem;
import com.team.dating_backend.recommendation.repository.RecommendationBatchRepository;
import com.team.dating_backend.recommendation.repository.RecommendationItemRepository;
import com.team.dating_backend.recommendation.repository.RecommendationPreferenceRepository;
import com.team.dating_backend.recommendation.service.RecommendationBatchCreateService;
import com.team.dating_backend.recommendation.service.RecommendationHistoryCleanupService;
import com.team.dating_backend.recommendation.service.RecommendationItemGetService;
import com.team.dating_backend.recommendation.service.RecommendationPreferenceService;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.repository.UserRepository;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.hibernate.SessionFactory;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(
    classes = RecommendationRefreshIntegrationTest.TestApplication.class,
    properties = {
        "spring.config.name=recommendation-refresh-test",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.generate_statistics=true"
    }
)
@Testcontainers
class RecommendationRefreshIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private RecommendationBatchCreateService service;
    @Autowired
    private RecommendationItemGetService queryService;
    @Autowired
    private RecommendationHistoryCleanupService cleanupService;
    @Autowired
    private RecommendationPreferenceService preferenceService;
    @Autowired
    private PublisherLockRepository locks;
    @Autowired
    private RecommendationItemRepository items;
    @Autowired
    private RecommendationBatchRepository batches;
    @Autowired
    private RecommendationPreferenceRepository preferences;
    @Autowired
    private UserRepository users;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @MockitoBean
    private ProfileImageGetService imageService;

    private Long requesterId;

    @BeforeEach
    void setUp() {
        items.deleteAllInBatch();
        batches.deleteAllInBatch();
        jdbc.update("delete from profiles");
        preferences.deleteAllInBatch();
        users.deleteAllInBatch();
        requesterId = activeUser(Gender.MALE);
        given(imageService.getProfileImagesByUserIds(anyCollection())).willReturn(Map.of());
    }

    @Test
    void 반복_생성과_동일_후보_갱신은_기존_ID를_유지하고_엔티티를_쓰지_않는다() {
        candidate();
        long batchId = create();
        List<Long> originalIds = itemIds(batchId);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        assertThat(create()).isEqualTo(batchId);
        service.refreshRecommendationBatch(requesterId);

        assertThat(itemIds(batchId)).isEqualTo(originalIds);
        assertThat(statistics.getEntityInsertCount()).isZero();
        assertThat(statistics.getEntityUpdateCount()).isZero();
        assertThat(statistics.getEntityDeleteCount()).isZero();
        assertThat(batches.count()).isEqualTo(1);
    }

    @Test
    void 커서_Item_삭제와_후보_추가_후에도_같은_목록에서_다음_페이지를_조회한다() {
        for (int i = 0; i < 21; i++) {
            candidate();
        }
        long batchId = create();
        var first = queryService.getRecommendationItems(requesterId, batchId, null);
        Long cursor = first.pageInfo().nextCursor();
        Long removedUser = first.items().getLast().candidate().userId();
        Long retainedItemId = first.items().getFirst().itemId();
        jdbc.update("update users set status = 'WITHDRAWN' where id = ?", removedUser);
        Long addedUser = candidate();

        service.refreshRecommendationBatch(requesterId);
        var next = queryService.getRecommendationItems(requesterId, batchId, cursor);

        assertThat(items.existsById(cursor)).isFalse();
        assertThat(items.existsById(retainedItemId)).isTrue();
        assertThat(next.items()).hasSize(2);
        assertThat(next.items()).extracting(item -> item.candidate().userId())
            .contains(addedUser).doesNotContain(removedUser);
        assertThat(next.items()).allSatisfy(item -> assertThat(item.itemId()).isGreaterThan(cursor));
        assertThat(batches.count()).isEqualTo(1);
    }

    @Test
    void 추가_실패시_먼저_수행한_제외도_롤백한다() throws SQLException {
        Long removed = candidate();
        long batchId = create();
        List<Long> originalIds = itemIds(batchId);
        jdbc.update("update users set status = 'WITHDRAWN' where id = ?", removed);
        candidate();
        executeAsRoot("""
            create trigger fail_recommendation_insert before insert on recommendation_items
            for each row signal sqlstate '45000' set message_text = 'forced failure'
            """);
        try {
            assertThatThrownBy(() -> service.refreshRecommendationBatch(requesterId))
                .isInstanceOf(RuntimeException.class);
            assertThat(itemIds(batchId)).isEqualTo(originalIds);
        } finally {
            executeAsRoot("drop trigger fail_recommendation_insert");
        }
        service.refreshRecommendationBatch(requesterId);
        assertThat(itemIds(batchId)).hasSize(1).doesNotContainAnyElementsOf(originalIds);
    }

    @Test
    void 같은_사용자의_동시_갱신은_후보를_중복_저장하지_않는다() throws Exception {
        candidate();
        long batchId = create();
        candidate();

        var first = CompletableFuture.runAsync(() -> service.refreshRecommendationBatch(requesterId));
        var second = CompletableFuture.runAsync(() -> service.refreshRecommendationBatch(requesterId));
        CompletableFuture.allOf(first, second).get(15, TimeUnit.SECONDS);

        assertThat(items.findAllByRecommendationBatchId(batchId))
            .extracting(RecommendationItem::getCandidateUserId).hasSize(2).doesNotHaveDuplicates();
        assertThat(batches.count()).isEqualTo(1);
    }

    @Test
    void 과거_Item을_청크로_정리하고_활성_목록은_보존한다() {
        Long candidateId = candidate();
        long activeId = create();
        var history = batches.save(new com.team.dating_backend.recommendation.entity.RecommendationBatch(
            requesterId, LocalDateTime.now()));
        items.save(new RecommendationItem(history.getId(), candidateId, 1));
        jdbc.update("update recommendation_batches set deleted_at = now(6) where id = ?", history.getId());

        assertThat(cleanupService.purgeChunk(1)).isEqualTo(2);

        assertThat(batches.existsById(history.getId())).isFalse();
        assertThat(items.findAllByRecommendationBatchId(activeId)).hasSize(1);
        assertThat(cleanupService.purgeChunk(1)).isZero();
    }

    @Test
    void 정기_갱신과_선호_변경이_겹쳐도_최종_조건에_맞는_목록을_유지한다() throws Exception {
        candidate();
        long batchId = create();
        CompletableFuture<Void> scheduled;
        CompletableFuture<Void> changed;
        try (var blocker = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())) {
            blocker.setAutoCommit(false);
            try {
                try (var statement = blocker.prepareStatement("select id from users where id = ? for update")) {
                    statement.setLong(1, requesterId);
                    try (var result = statement.executeQuery()) {
                        assertThat(result.next()).isTrue();
                    }
                }
                scheduled = CompletableFuture.runAsync(() -> service.refreshRecommendationBatch(requesterId));
                changed = CompletableFuture.runAsync(() -> preferenceService.savePreferences(requesterId,
                    new RecommendationPreferenceSaveRequest(null, null, null, 160, List.of(), List.of(), List.of())));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                int waiting = 0;
                try (var statement = blocker.createStatement()) {
                    do {
                        try (var result = statement
                            .executeQuery("select count(*) from performance_schema.data_lock_waits")) {
                            result.next();
                            waiting = result.getInt(1);
                        }
                        if (waiting >= 2) {
                            break;
                        }
                        Thread.sleep(10);
                    } while (System.nanoTime() < deadline);
                }
                assertThat(waiting).isGreaterThanOrEqualTo(2);
                blocker.commit();
            } finally {
                blocker.rollback();
            }
        }
        CompletableFuture.allOf(scheduled, changed).get(15, TimeUnit.SECONDS);

        assertThat(preferences.findById(requesterId).orElseThrow().getMaxHeight()).isEqualTo((short) 160);
        assertThat(itemIds(batchId)).isEmpty();
        assertThat(batches.count()).isEqualTo(1);
    }

    @Test
    void 실행_잠금은_다른_소유자를_거부하고_소유자만_해제할_수_있다() {
        String name = "recommendation-refresh";
        assertThat(locks.tryAcquireOrExtend(name, "first", 300_000_000L)).isEqualTo(1);
        try {
            assertThat(locks.tryAcquireOrExtend(name, "second", 300_000_000L)).isZero();
            assertThat(locks.release(name, "second")).isZero();
            assertThat(locks.release(name, "first")).isEqualTo(1);
            assertThat(locks.tryAcquireOrExtend(name, "second", 300_000_000L)).isEqualTo(1);
        } finally {
            locks.release(name, "first");
            locks.release(name, "second");
        }
    }

    private void executeAsRoot(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
            var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private List<Long> itemIds(long batchId) {
        return items.findAllByRecommendationBatchId(batchId).stream()
            .map(RecommendationItem::getId).sorted().toList();
    }

    private long create() {
        return service.createRecommendationBatch(requesterId).orElseThrow().batchId();
    }

    private Long candidate() {
        Long id = activeUser(Gender.FEMALE);
        jdbc.update("""
            insert into profiles (user_id, nickname, height, religion, drinking, smoking, created_at, updated_at)
            values (?, ?, 170, 'NONE', 'NEVER', 'NON_SMOKER', now(6), now(6))
            """, id, "후보" + id);
        return id;
    }

    private Long activeUser(Gender gender) {
        User user = User.create("회원", LocalDate.of(1996, 1, 1), gender, LocalDateTime.now());
        user.activate(LocalDateTime.now());
        return users.save(user).getId();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan("com.team.dating_backend")
    @EnableJpaRepositories("com.team.dating_backend")
    @Import(
        {RecommendationBatchCreateService.class, RecommendationItemGetService.class,
            RecommendationHistoryCleanupService.class, RecommendationPreferenceService.class}
    )
    static class TestApplication {}
}
