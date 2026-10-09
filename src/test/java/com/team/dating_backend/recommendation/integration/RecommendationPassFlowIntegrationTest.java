package com.team.dating_backend.recommendation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.team.dating_backend.TestJwtSecret;
import com.team.dating_backend.auth.config.JwtProperties;
import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.chat.service.ChatRoomCreateService;
import com.team.dating_backend.common.exception.GlobalExceptionHandler;
import com.team.dating_backend.matching.enums.LikeStatus;
import com.team.dating_backend.matching.repository.LikeRepository;
import com.team.dating_backend.matching.service.LikeSendService;
import com.team.dating_backend.profile.service.ProfileImageGetService;
import com.team.dating_backend.recommendation.controller.RecommendationBatchController;
import com.team.dating_backend.recommendation.controller.RecommendationPassController;
import com.team.dating_backend.recommendation.entity.RecommendationBatch;
import com.team.dating_backend.recommendation.entity.RecommendationItem;
import com.team.dating_backend.recommendation.repository.RecommendationBatchRepository;
import com.team.dating_backend.recommendation.repository.RecommendationItemRepository;
import com.team.dating_backend.recommendation.service.RecommendationBatchCreateService;
import com.team.dating_backend.recommendation.service.RecommendationBatchGetService;
import com.team.dating_backend.recommendation.service.RecommendationItemGetService;
import com.team.dating_backend.recommendation.service.RecommendationPassSaveService;
import com.team.dating_backend.security.ApiAccessDeniedHandler;
import com.team.dating_backend.security.ApiAuthenticationEntryPoint;
import com.team.dating_backend.security.config.SecurityConfig;
import com.team.dating_backend.security.config.SecurityProperties;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.repository.UserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    classes = RecommendationPassFlowIntegrationTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.config.name=recommendation-pass-flow-test",
        "server.address=127.0.0.1",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "app.security.allowed-origins=http://localhost:5173",
        "jwt.service-expiration-minutes=60",
        "jwt.refresh-expiration-days=30"
    }
)
@Testcontainers
class RecommendationPassFlowIntegrationTest {

    private static final String ENDPOINT = "/api/v1/members/me/recommendation-passes/";
    private static final String TEST_JWT_SECRET = TestJwtSecret.generate();

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @DynamicPropertySource
    static void registerJwtSecret(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> TEST_JWT_SECRET);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RecommendationBatchRepository batchRepository;

    @Autowired
    private RecommendationItemRepository itemRepository;

    @Autowired
    private RecommendationBatchCreateService batchCreateService;

    @Autowired
    private LikeSendService likeSendService;

    @Autowired
    private LikeRepository likeRepository;

    @MockitoBean
    private ProfileImageGetService profileImageGetService;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    private Long userId;
    private Long targetId;
    private Long nextId;
    private Long batchId;
    private String token;
    private String targetToken;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from recommendation_passes");
        jdbcTemplate.update("delete from user_blocks");
        jdbcTemplate.update("delete from likes");
        jdbcTemplate.update("delete from recommendation_items");
        jdbcTemplate.update("delete from recommendation_batches");
        jdbcTemplate.update("delete from profiles");
        userRepository.deleteAllInBatch();
        userId = activeUser(Gender.MALE);
        targetId = activeUser(Gender.FEMALE);
        nextId = activeUser(Gender.FEMALE);
        batchId = batch(userId, List.of(targetId, nextId));
        batch(targetId, List.of(userId));
        token = jwtService.createServiceAuthToken(userId);
        targetToken = jwtService.createServiceAuthToken(targetId);
        given(profileImageGetService.getProfileImagesByMemberIds(List.of(nextId)))
            .willReturn(Map.of());
        given(profileImageGetService.getProfileImagesByMemberIds(List.of(userId)))
            .willReturn(Map.of());
    }

    @Test
    void 신규_패스는_201을_반환하고_재요청은_200과_최초_시각을_유지한다() throws Exception {
        HttpResponse<String> created = pass(targetId.toString(), token);
        HttpResponse<String> repeated = pass(targetId.toString(), token);

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(json(created).path("message").asString())
            .isEqualTo("recommendation_pass_success");
        assertThat(json(created).path("data").path("targetMemberId").asLong()).isEqualTo(targetId);
        assertThat(json(created).path("data").path("permanent").asBoolean()).isTrue();
        assertThat(json(created).path("data").path("passedAt").asString()).isNotBlank();
        assertThat(repeated.statusCode()).isEqualTo(200);
        assertThat(json(repeated).path("message").asString())
            .isEqualTo("recommendation_pass_already_exists");
        assertThat(json(repeated).path("data")).isEqualTo(json(created).path("data"));
        assertThat(passCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "select passer_user_id from recommendation_passes", Long.class)).isEqualTo(userId);
    }

    @Test
    void 패스는_기존_카드와_새_배치에서_제외하고_반대_방향_추천은_유지한다() throws Exception {
        assertThat(pass(targetId.toString(), token).statusCode()).isEqualTo(201);

        HttpResponse<String> cards = get("/api/v1/recommendation-batches/" + batchId + "/items", token);
        assertThat(cards.statusCode()).isEqualTo(200);
        JsonNode items = json(cards).path("data").path("items");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).path("candidate").path("memberId").asLong()).isEqualTo(nextId);
        assertThat(itemRepository.existsEligibleCandidateInActiveBatch(userId, targetId)).isFalse();
        assertThat(itemRepository.existsEligibleCandidateInActiveBatch(targetId, userId)).isTrue();
        assertThat(itemRepository.count()).isEqualTo(3);

        Long newBatchId = batchCreateService.createRecommendationBatch(userId).orElseThrow().batchId();
        assertThat(jdbcTemplate.queryForList(
            "select candidate_user_id from recommendation_items where recommendation_batch_id = ?",
            Long.class, newBatchId)).containsExactly(nextId);
        assertThat(passCount()).isEqualTo(1);
    }

    @Test
    void 패스당한_상대가_좋아요를_보내면_받은_좋아요에_나타나고_프로필_관계가_유지된다()
        throws Exception {
        assertThat(pass(targetId.toString(), token).statusCode()).isEqualTo(201);

        assertThat(likeSendService.sendLike(targetId, userId).status()).isEqualTo(LikeStatus.PENDING);
        assertThat(likeRepository.findReceivedPendingLikes(userId, null, PageRequest.of(0, 20)))
            .hasSize(1);
        assertThat(likeRepository.hasProfileViewAccessBetween(userId, targetId)).isTrue();
        assertThat(itemRepository.existsEligibleCandidateInActiveBatch(userId, targetId)).isFalse();
    }

    @Test
    void 마지막_후보를_패스해도_새_배치를_자동_생성하지_않는다() throws Exception {
        assertThat(pass(targetId.toString(), token).statusCode()).isEqualTo(201);
        assertThat(pass(nextId.toString(), token).statusCode()).isEqualTo(201);

        HttpResponse<String> cards = get("/api/v1/recommendation-batches/" + batchId + "/items", token);
        assertThat(cards.statusCode()).isEqualTo(200);
        assertThat(json(cards).path("data").path("items").size()).isZero();
        assertThat(json(cards).path("data").path("pageInfo").path("hasNext").asBoolean())
            .isFalse();
        assertThat(batchRepository.count()).isEqualTo(2);
        assertThat(itemRepository.count()).isEqualTo(3);
        assertThat(batchRepository.findByUserIdAndDeletedAtIsNull(userId).orElseThrow().getId())
            .isEqualTo(batchId);
    }

    @Test
    void 패스한_카드의_커서로_다음_카드를_조회할_수_있다() throws Exception {
        Long cursor = jdbcTemplate.queryForObject("""
            select id from recommendation_items
            where recommendation_batch_id = ? and candidate_user_id = ?
            """, Long.class, batchId, targetId);
        assertThat(pass(targetId.toString(), token).statusCode()).isEqualTo(201);

        HttpResponse<String> cards = get(
            "/api/v1/recommendation-batches/" + batchId + "/items?cursor=" + cursor, token);
        assertThat(cards.statusCode()).isEqualTo(200);
        assertThat(json(cards).path("data").path("items").get(0)
            .path("candidate").path("memberId").asLong()).isEqualTo(nextId);
    }

    @Test
    void 저장_실패는_500을_반환하고_대상을_추천에서_제외하지_않는다() throws Exception {
        executeAsRoot("""
            create trigger fail_pass_insert before insert on recommendation_passes
            for each row signal sqlstate '45000' set message_text = 'test insert failure'
            """);
        try {
            assertError(pass(targetId.toString(), token), 500, "INTERNAL_SERVER_ERROR");
            assertThat(passCount()).isZero();
            assertThat(itemRepository.existsEligibleCandidateInActiveBatch(userId, targetId)).isTrue();
            assertThat(itemRepository.count()).isEqualTo(3);
        } finally {
            executeAsRoot("drop trigger fail_pass_insert");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-1", "9223372036854775808"})
    void 잘못된_ID에는_400_INVALID_REQUEST를_반환한다(String target) throws Exception {
        assertError(pass(target, token), 400, "INVALID_REQUEST");
        assertThat(passCount()).isZero();
    }

    @Test
    void 자기_자신_패스는_422를_반환한다() throws Exception {
        assertError(pass(userId.toString(), token), 422, "SELF_PASS_NOT_ALLOWED");
        assertThat(passCount()).isZero();
    }

    @Test
    void 인증이_없으면_401을_반환한다() throws Exception {
        assertError(pass(targetId.toString(), null), 401, "AUTH_REQUIRED");
        assertThat(passCount()).isZero();
    }

    @Test
    void 비활성_요청자는_403을_반환한다() throws Exception {
        jdbcTemplate.update("update users set status = 'SUSPENDED' where id = ?", userId);

        assertError(pass(targetId.toString(), token), 403, "REQUESTER_NOT_ACTIVE");
        assertThat(passCount()).isZero();
    }

    @Test
    void 존재하지_않는_대상과_현재_추천에_없는_대상은_404를_반환한다() throws Exception {
        assertError(pass(Long.MAX_VALUE + "", token), 404, "RECOMMENDATION_TARGET_NOT_AVAILABLE");
        Long unrelated = activeUser(Gender.FEMALE);
        assertError(pass(unrelated.toString(), token), 404, "RECOMMENDATION_TARGET_NOT_AVAILABLE");
        assertThat(passCount()).isZero();
    }

    @Test
    void 카드_노출_이후_대상_탈퇴나_차단이_생기면_신규_패스를_저장하지_않는다() throws Exception {
        jdbcTemplate.update("update users set status = 'WITHDRAWN' where id = ?", targetId);
        assertError(pass(targetId.toString(), token), 404, "RECOMMENDATION_TARGET_NOT_AVAILABLE");
        jdbcTemplate.update("""
            insert into user_blocks (blocker_user_id, blocked_user_id, blocked_at)
            values (?, ?, now(6))
            """, nextId, userId);
        assertError(pass(nextId.toString(), token), 404, "RECOMMENDATION_TARGET_NOT_AVAILABLE");
        assertThat(passCount()).isZero();
        jdbcTemplate.update("delete from user_blocks");
    }

    @Test
    void 기존_패스는_배치가_교체되거나_상대가_탈퇴해도_재요청_결과를_유지한다() throws Exception {
        HttpResponse<String> created = pass(targetId.toString(), token);
        assertThat(created.statusCode()).isEqualTo(201);
        jdbcTemplate.update("update recommendation_batches set deleted_at = now(6) where user_id = ?",
            userId);
        jdbcTemplate.update("update users set status = 'WITHDRAWN' where id = ?", targetId);

        HttpResponse<String> repeated = pass(targetId.toString(), token);
        assertThat(repeated.statusCode()).isEqualTo(200);
        assertThat(json(repeated).path("data")).isEqualTo(json(created).path("data"));
        assertThat(passCount()).isEqualTo(1);
    }

    @Test
    void 동시에_같은_상대를_패스해도_한_번만_생성하고_같은_시각을_반환한다() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> requests = List.of(
            sendPassAsync(targetId, token), sendPassAsync(targetId, token),
            sendPassAsync(targetId, token));
        List<HttpResponse<String>> responses = requests.stream()
            .map(request -> request.orTimeout(10, TimeUnit.SECONDS).join()).toList();
        assertThat(responses).extracting(HttpResponse::statusCode)
            .containsExactlyInAnyOrder(201, 200, 200);
        String passedAt = json(responses.getFirst()).path("data").path("passedAt").asString();
        for (HttpResponse<String> response : responses) {
            assertThat(json(response).path("data").path("passedAt").asString())
                .isEqualTo(passedAt);
        }
        assertThat(passCount()).isEqualTo(1);
    }

    @Test
    void UNIQUE_충돌로_실패한_트랜잭션은_종료하고_최초_패스를_다시_조회한다() throws Exception {
        LocalDateTime firstPassedAt = LocalDateTime.of(2026, 10, 9, 12, 0, 0, 123456000);
        try (Connection blocker = DriverManager.getConnection(
            MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("""
                insert into recommendation_passes (passer_user_id, passed_user_id, created_at)
                values (?, ?, ?)
                """)) {
                statement.setLong(1, userId);
                statement.setLong(2, targetId);
                statement.setObject(3, firstPassedAt);
                statement.executeUpdate();
                List<CompletableFuture<HttpResponse<String>>> requests = List.of(
                    sendPassAsync(targetId, token), sendPassAsync(targetId, token),
                    sendPassAsync(targetId, token));
                awaitWaitingPassInserts(3);
                blocker.commit();
                List<HttpResponse<String>> responses = requests.stream()
                    .map(request -> request.orTimeout(10, TimeUnit.SECONDS).join()).toList();
                for (HttpResponse<String> response : responses) {
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(json(response).path("message").asString())
                        .isEqualTo("recommendation_pass_already_exists");
                    assertThat(LocalDateTime.parse(json(response).path("data").path("passedAt").asString()))
                        .isEqualTo(firstPassedAt);
                }
            }
        }
        assertThat(passCount()).isEqualTo(1);
    }

    @Test
    void 반대_방향_동시_패스는_서로_독립적으로_생성된다() {
        CompletableFuture<HttpResponse<String>> forward = sendPassAsync(targetId, token);
        CompletableFuture<HttpResponse<String>> reverse = sendPassAsync(userId, targetToken);

        assertThat(forward.orTimeout(10, TimeUnit.SECONDS).join().statusCode()).isEqualTo(201);
        assertThat(reverse.orTimeout(10, TimeUnit.SECONDS).join().statusCode()).isEqualTo(201);
        assertThat(passCount()).isEqualTo(2);
    }

    @Test
    void V3는_중복_자기패스와_존재하지_않는_회원_참조를_DB에서_차단한다() {
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from flyway_schema_history where version = '3' and success = 1",
            Integer.class)).isEqualTo(1);
        insertPass(userId, targetId);
        assertConstraint(() -> insertPass(userId, targetId), "uk_recommendation_pass_direction");
        assertConstraint(() -> insertPass(userId, userId), "chk_recommendation_pass_not_self");
        assertConstraint(() -> insertPass(Long.MAX_VALUE, targetId), "fk_recommendation_pass_passer");
        assertConstraint(() -> insertPass(userId, Long.MAX_VALUE), "fk_recommendation_pass_passed");
        insertPass(targetId, userId);
        assertThat(passCount()).isEqualTo(2);
    }

    private void assertConstraint(Runnable insert, String constraint) {
        assertThatThrownBy(insert::run).isInstanceOf(DataAccessException.class)
            .hasStackTraceContaining(constraint);
    }

    private void insertPass(Long passer, Long passed) {
        jdbcTemplate.update("""
            insert into recommendation_passes (passer_user_id, passed_user_id, created_at)
            values (?, ?, now(6))
            """, passer, passed);
    }

    private void executeAsRoot(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
            MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
            var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void awaitWaitingPassInserts(int expected) throws Exception {
        try (Connection observer = DriverManager.getConnection(
            MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
            var statement = observer.prepareStatement("""
                select count(distinct waits.REQUESTING_ENGINE_TRANSACTION_ID)
                from performance_schema.data_lock_waits waits
                join performance_schema.data_locks locks
                  on locks.ENGINE = waits.ENGINE
                 and locks.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                where locks.OBJECT_SCHEMA = ? and locks.OBJECT_NAME = 'recommendation_passes'
                  and locks.INDEX_NAME = 'uk_recommendation_pass_direction'
                  and locks.LOCK_STATUS = 'WAITING'
                """)) {
            statement.setString(1, MYSQL.getDatabaseName());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            int waitingRequests;
            do {
                try (var result = statement.executeQuery()) {
                    result.next();
                    waitingRequests = result.getInt(1);
                }
                if (waitingRequests == expected) {
                    return;
                }
                Thread.sleep(25);
            } while (System.nanoTime() < deadline);
            assertThat(waitingRequests).as("동시에 패스 UNIQUE 검사를 기다리는 요청 수").isEqualTo(expected);
        }
    }

    private Long activeUser(Gender gender) {
        User user = User.create("테스트", LocalDate.of(1996, 1, 1), gender, LocalDateTime.now());
        user.activate(LocalDateTime.now());
        Long id = userRepository.saveAndFlush(user).getId();
        jdbcTemplate.update("""
            insert into profiles (user_id, nickname, created_at, updated_at)
            values (?, ?, now(6), now(6))
            """, id, "회원" + id);
        return id;
    }

    private Long batch(Long owner, List<Long> candidates) {
        Long id = batchRepository.save(new RecommendationBatch(owner, LocalDateTime.now())).getId();
        for (int index = 0; index < candidates.size(); index++) {
            itemRepository.save(new RecommendationItem(id, candidates.get(index), index + 1));
        }
        return id;
    }

    private int passCount() {
        return jdbcTemplate.queryForObject("select count(*) from recommendation_passes", Integer.class);
    }

    private HttpResponse<String> pass(String target, String authToken) throws Exception {
        return httpClient.send(passRequest(target, authToken), HttpResponse.BodyHandlers.ofString());
    }

    private CompletableFuture<HttpResponse<String>> sendPassAsync(Long target, String authToken) {
        return httpClient.sendAsync(passRequest(target.toString(), authToken),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest passRequest(String target, String authToken) {
        return request(ENDPOINT + target, authToken).PUT(HttpRequest.BodyPublishers.noBody()).build();
    }

    private HttpResponse<String> get(String path, String authToken) throws Exception {
        return httpClient.send(request(path, authToken).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path, String authToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (authToken != null) {
            builder.header("Authorization", "Bearer " + authToken);
        }
        return builder;
    }

    private JsonNode json(HttpResponse<String> response) {
        return objectMapper.readTree(response.body());
    }

    private void assertError(HttpResponse<String> response, int status, String errorCode) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(json(response).path("errorCode").asString()).isEqualTo(errorCode);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.team.dating_backend")
    @EnableJpaRepositories(
        basePackages = {
            "com.team.dating_backend.user.repository",
            "com.team.dating_backend.recommendation.repository",
            "com.team.dating_backend.matching.repository",
            "com.team.dating_backend.profile.repository",
            "com.team.dating_backend.chat.repository"
        }
    )
    @EnableConfigurationProperties({JwtProperties.class, SecurityProperties.class})
    @Import(
        {
            SecurityConfig.class,
            RecommendationPassController.class,
            RecommendationPassSaveService.class,
            RecommendationBatchController.class,
            RecommendationBatchCreateService.class,
            RecommendationBatchGetService.class,
            RecommendationItemGetService.class,
            LikeSendService.class,
            ChatRoomCreateService.class,
            GlobalExceptionHandler.class,
            JwtService.class,
            ApiAuthenticationEntryPoint.class,
            ApiAccessDeniedHandler.class
        }
    )
    static class TestApplication {}
}
