package com.team.dating_backend.recommendation.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.TestJwtSecret;
import com.team.dating_backend.auth.config.JwtProperties;
import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.common.exception.GlobalExceptionHandler;
import com.team.dating_backend.security.ApiAccessDeniedHandler;
import com.team.dating_backend.security.ApiAuthenticationEntryPoint;
import com.team.dating_backend.security.config.SecurityConfig;
import com.team.dating_backend.security.config.SecurityProperties;
import com.team.dating_backend.recommendation.controller.RecommendationPreferenceController;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.recommendation.repository.RecommendationPreferenceRepository;
import com.team.dating_backend.user.repository.UserRepository;
import com.team.dating_backend.recommendation.service.RecommendationPreferenceService;
import com.team.dating_backend.recommendation.service.RecommendationBatchCreateService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(
    classes = RecommendationPreferenceFlowIntegrationTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.config.name=recommendation-preference-flow-test",
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
class RecommendationPreferenceFlowIntegrationTest {

    private static final String ENDPOINT = "/api/v1/users/me/preferences";
    private static final String TEST_JWT_SECRET = TestJwtSecret.generate();
    private static final String SAVED_PREFERENCE = """
        {
          "minAge": 25,
          "maxAge": 30,
          "minHeight": 160,
          "maxHeight": 180,
          "religion": ["NONE", "CATHOLIC"],
          "drinking": ["NEVER", "SOCIAL"],
          "smoking": ["NON_SMOKER", "OCCASIONAL"]
        }
        """;
    private static final String UNRESTRICTED_PREFERENCE = """
        {
          "minAge": null,
          "maxAge": null,
          "minHeight": null,
          "maxHeight": null,
          "religion": [],
          "drinking": [],
          "smoking": []
        }
        """;

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
    private RecommendationPreferenceRepository recommendationPreferenceRepository;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    private Long userId;
    private Long otherUserId;
    private String token;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from recommendation_items");
        jdbcTemplate.update("delete from recommendation_batches");
        jdbcTemplate.update("delete from profiles");
        recommendationPreferenceRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        userId = createActiveUser("사용자");
        otherUserId = createActiveUser("다른회원");
        jdbcTemplate.update("update users set gender = 'FEMALE' where id = ?", otherUserId);
        jdbcTemplate.update("""
            insert into profiles (user_id, nickname, height, religion, drinking, smoking,
                created_at, updated_at)
            values (?, '추천상대', 170, 'NONE', 'NEVER', 'NON_SMOKER', now(6), now(6))
            """, otherUserId);
        token = jwtService.createServiceAuthToken(userId);
    }

    @Test
    void 첫_페이지_조회와_재조회는_기본_조건을_반환하고_DB에_저장하지_않는다() throws Exception {
        assertPreferenceResponse(getPreferences(token), UNRESTRICTED_PREFERENCE);
        assertPreferenceResponse(getPreferences(token), UNRESTRICTED_PREFERENCE);

        assertThat(recommendationPreferenceRepository.count()).isZero();
    }

    @Test
    void 인증된_회원의_조건을_저장하고_별도_GET에서_조회한다() throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(SAVED_PREFERENCE);
        body.put("userId", otherUserId);

        assertNoContent(putPreferences(token, body.toString()));
        Map<String, Object> stored = storedPreference();
        assertPreferenceResponse(getPreferences(token), SAVED_PREFERENCE);

        assertThat(stored.get("user_id")).isEqualTo(userId);
        assertThat(stored.get("created_at")).isEqualTo(stored.get("updated_at"));
        assertThat(storedPreference()).isEqualTo(stored);
        assertPreferenceResponse(getPreferences(jwtService.createServiceAuthToken(otherUserId)),
            UNRESTRICTED_PREFERENCE);
        assertThat(recommendationPreferenceRepository.existsById(otherUserId)).isFalse();
        assertThat(recommendationPreferenceRepository.count()).isEqualTo(1);
    }

    @Test
    void 수정은_기존_행을_전체_대체하고_생성_시각을_유지한다() throws Exception {
        assertNoContent(putPreferences(token, SAVED_PREFERENCE));
        Map<String, Object> before = storedPreference();
        ObjectNode replacement = (ObjectNode) objectMapper.readTree(UNRESTRICTED_PREFERENCE);
        replacement.put("minAge", 28);
        replacement.put("maxHeight", 190);
        replacement.set("religion", objectMapper.readTree("[\"BUDDHIST\"]"));

        assertNoContent(putPreferences(token, replacement.toString()));
        assertPreferenceResponse(getPreferences(token), replacement.toString());

        Map<String, Object> after = storedPreference();
        assertThat(after.get("user_id")).isEqualTo(before.get("user_id"));
        assertThat(after.get("created_at")).isEqualTo(before.get("created_at"));
        assertThat(after.get("updated_at")).isNotEqualTo(before.get("updated_at"));
        assertThat(recommendationPreferenceRepository.count()).isEqualTo(1);
    }

    @Test
    void 같은_요청과_선택_순서만_바꾼_요청은_수정_시각을_유지한다() throws Exception {
        assertNoContent(putPreferences(token, SAVED_PREFERENCE));
        Map<String, Object> before = storedPreference();
        Long batchId = activeBatchId();
        assertNoContent(putPreferences(token, SAVED_PREFERENCE));
        ObjectNode reordered = (ObjectNode) objectMapper.readTree(SAVED_PREFERENCE);
        reordered.set("religion", objectMapper.readTree("[\"CATHOLIC\", \"NONE\"]"));
        reordered.set("drinking", objectMapper.readTree("[\"SOCIAL\", \"NEVER\"]"));
        reordered.set("smoking", objectMapper.readTree("[\"OCCASIONAL\", \"NON_SMOKER\"]"));

        assertNoContent(putPreferences(token, reordered.toString()));
        assertPreferenceResponse(getPreferences(token), SAVED_PREFERENCE);
        assertThat(storedPreference()).isEqualTo(before);
        assertThat(activeBatchId()).isEqualTo(batchId);
        assertThat(batchCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"item_insert", "batch_update"})
    void 추천_갱신_중간_실패는_조건과_추천_결과를_함께_롤백한다(String failureStage)
        throws Exception {
        assertNoContent(putPreferences(token, SAVED_PREFERENCE));
        Map<String, Object> before = storedPreference();
        Long batchId = activeBatchId();
        ObjectNode replacement = (ObjectNode) objectMapper.readTree(SAVED_PREFERENCE);
        replacement.put("maxHeight", 190);
        String trigger = failureStage.equals("item_insert") ? """
            create trigger test_recommendation_write_failure
            after insert on recommendation_items
            for each row
            signal sqlstate '45000' set message_text = 'forced recommendation item failure'
            """ : """
            create trigger test_recommendation_write_failure
            before update on recommendation_batches
            for each row
            signal sqlstate '45000' set message_text = 'forced recommendation batch update failure'
            """;

        try {
            executeTriggerSql(trigger);

            assertError(putPreferences(token, replacement.toString()), 500, "INTERNAL_SERVER_ERROR");
            assertThat(storedPreference()).isEqualTo(before);
            assertThat(activeBatchId()).isEqualTo(batchId);
            assertThat(batchCount()).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                "select count(*) from recommendation_items where recommendation_batch_id = ?",
                Long.class, batchId)).isEqualTo(1);
        } finally {
            executeTriggerSql("drop trigger if exists test_recommendation_write_failure");
        }

        assertNoContent(putPreferences(token, replacement.toString()));
        assertThat(activeBatchId()).isNotEqualTo(batchId);
        assertThat(batchCount()).isEqualTo(2);
    }

    @Test
    void 후보가_없으면_조건_저장과_기존_배치_종료를_정상_커밋한다() throws Exception {
        assertNoContent(putPreferences(token, SAVED_PREFERENCE));
        assertThat(activeBatchId()).isNotNull();
        jdbcTemplate.update("update users set status = 'WITHDRAWN' where id = ?", otherUserId);
        ObjectNode replacement = (ObjectNode) objectMapper.readTree(SAVED_PREFERENCE);
        replacement.put("maxHeight", 190);

        assertNoContent(putPreferences(token, replacement.toString()));

        assertPreferenceResponse(getPreferences(token), replacement.toString());
        assertThat(activeBatchId()).isNull();
        assertThat(batchCount()).isEqualTo(1);
    }

    @Test
    void 초기화는_행을_유지하고_null_배열을_빈_배열로_저장한다() throws Exception {
        assertNoContent(putPreferences(token, SAVED_PREFERENCE));
        assertThat(activeBatchGenerationType()).isEqualTo("PREFERENCE");
        Map<String, Object> before = storedPreference();
        ObjectNode reset = (ObjectNode) objectMapper.readTree(UNRESTRICTED_PREFERENCE);
        reset.putNull("religion");
        reset.putNull("drinking");
        reset.putNull("smoking");

        assertNoContent(putPreferences(token, reset.toString()));
        assertThat(activeBatchGenerationType()).isEqualTo("DEFAULT");
        assertPreferenceResponse(getPreferences(token), UNRESTRICTED_PREFERENCE);
        Map<String, Object> after = storedPreference();
        assertThat(after.get("created_at")).isEqualTo(before.get("created_at"));
        assertThat(after.get("updated_at")).isNotEqualTo(before.get("updated_at"));
        assertThat(after.get("deleted_at")).isNull();
        assertEmptyJsonArrays();
        assertNoContent(putPreferences(token, UNRESTRICTED_PREFERENCE));
        assertThat(storedPreference()).isEqualTo(after);
        assertThat(recommendationPreferenceRepository.count()).isEqualTo(1);
    }

    @Test
    void 최초_요청이_초기화여도_새_행을_생성한다() throws Exception {
        assertNoContent(putPreferences(token, UNRESTRICTED_PREFERENCE));
        assertThat(activeBatchGenerationType()).isEqualTo("DEFAULT");
        assertPreferenceResponse(getPreferences(token), UNRESTRICTED_PREFERENCE);

        Map<String, Object> stored = storedPreference();
        assertThat(stored.get("created_at")).isEqualTo(stored.get("updated_at"));
        assertThat(stored.get("deleted_at")).isNull();
        assertEmptyJsonArrays();
        assertThat(recommendationPreferenceRepository.count()).isEqualTo(1);
    }

    @ParameterizedTest(name = "동일한 조건 요청: {0}")
    @ValueSource(booleans = {true, false})
    void 동시_최초_PUT은_두_요청이_성공하고_한_행만_저장한다(boolean sameConditions)
        throws Exception {
        String secondBody = sameConditions ? SAVED_PREFERENCE : UNRESTRICTED_PREFERENCE;

        try (Connection blocker = DriverManager.getConnection(
            MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())) {
            blocker.setAutoCommit(false);
            try {
                try (PreparedStatement statement = blocker.prepareStatement(
                    "select id from users where id = ? for update")) {
                    statement.setLong(1, userId);
                    try (ResultSet result = statement.executeQuery()) {
                        assertThat(result.next()).isTrue();
                    }
                }

                CompletableFuture<HttpResponse<String>> first = putPreferencesAsync(SAVED_PREFERENCE);
                awaitWaitingRequests(blocker, 1);
                CompletableFuture<HttpResponse<String>> second = putPreferencesAsync(secondBody);
                awaitWaitingRequests(blocker, 2);

                assertThat(first.isDone()).isFalse();
                assertThat(second.isDone()).isFalse();
                assertThat(recommendationPreferenceRepository.count()).isZero();
                assertPreferenceResponse(getPreferences(token), UNRESTRICTED_PREFERENCE);

                blocker.commit();
                assertNoContent(first.get(10, TimeUnit.SECONDS));
                assertNoContent(second.get(10, TimeUnit.SECONDS));
            } finally {
                blocker.rollback();
            }
        }

        HttpResponse<String> response = getPreferences(token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(objectMapper.readTree(response.body()).at("/data/preference"))
            .isIn(objectMapper.readTree(SAVED_PREFERENCE), objectMapper.readTree(secondBody));
        assertThat(storedPreference().get("user_id")).isEqualTo(userId);
        assertThat(recommendationPreferenceRepository.count()).isEqualTo(1);
        if (sameConditions) {
            Map<String, Object> stored = storedPreference();
            assertThat(stored.get("updated_at")).isEqualTo(stored.get("created_at"));
        }
        assertThat(batchCount()).isEqualTo(sameConditions ? 1L : 2L);
        assertThat(jdbcTemplate.queryForObject("""
            select count(*) from recommendation_batches
            where user_id = ? and deleted_at is null
            """, Long.class, userId)).isEqualTo(1L);
    }

    @ParameterizedTest(name = "최초 요청이 초기화: {0}")
    @ValueSource(booleans = {false, true})
    void 최초_저장_SQL이_실패하면_행이_남지_않고_재시도할_수_있다(boolean reset)
        throws Exception {
        String body = reset ? UNRESTRICTED_PREFERENCE : SAVED_PREFERENCE;
        try {
            executeTriggerSql("""
                create trigger test_preference_write_failure
                after insert on recommendation_preferences
                for each row
                signal sqlstate '45000' set message_text = 'forced preference insert failure'
                """);

            assertError(putPreferences(token, body), 500, "INTERNAL_SERVER_ERROR");
            assertThat(recommendationPreferenceRepository.count()).isZero();
            assertPreferenceResponse(getPreferences(token), UNRESTRICTED_PREFERENCE);
            assertThat(recommendationPreferenceRepository.count()).isZero();
        } finally {
            executeTriggerSql("drop trigger if exists test_preference_write_failure");
        }

        assertNoContent(putPreferences(token, body));
        assertPreferenceResponse(getPreferences(token), body);
        assertThat(recommendationPreferenceRepository.count()).isEqualTo(1);
        Map<String, Object> stored = storedPreference();
        assertThat(stored.get("created_at")).isEqualTo(stored.get("updated_at"));
        assertThat(stored.get("deleted_at")).isNull();
    }

    @ParameterizedTest(name = "수정 요청이 초기화: {0}")
    @ValueSource(booleans = {false, true})
    void 수정_SQL이_실패하면_값과_시각을_유지하고_재시도할_수_있다(boolean reset)
        throws Exception {
        assertNoContent(putPreferences(token, SAVED_PREFERENCE));
        Map<String, Object> before = storedPreference();
        ObjectNode replacement = (ObjectNode) objectMapper.readTree(SAVED_PREFERENCE);
        replacement.put("minAge", 26);
        replacement.put("maxHeight", 190);
        replacement.set("religion", objectMapper.readTree("[\"BUDDHIST\"]"));
        String body = reset ? UNRESTRICTED_PREFERENCE : replacement.toString();
        try {
            executeTriggerSql("""
                create trigger test_preference_write_failure
                after update on recommendation_preferences
                for each row
                signal sqlstate '45000' set message_text = 'forced preference update failure'
                """);

            assertError(putPreferences(token, body), 500, "INTERNAL_SERVER_ERROR");
            assertThat(storedPreference()).isEqualTo(before);
            assertPreferenceResponse(getPreferences(token), SAVED_PREFERENCE);
            assertThat(recommendationPreferenceRepository.count()).isEqualTo(1);
        } finally {
            executeTriggerSql("drop trigger if exists test_preference_write_failure");
        }

        assertNoContent(putPreferences(token, body));
        assertPreferenceResponse(getPreferences(token), body);
        Map<String, Object> after = storedPreference();
        assertThat(after.get("created_at")).isEqualTo(before.get("created_at"));
        assertThat(after.get("updated_at")).isNotEqualTo(before.get("updated_at"));
        assertThat(after.get("deleted_at")).isNull();
        assertThat(recommendationPreferenceRepository.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void 거절된_입력은_기존_조건을_유지하고_새_행을_생성하지_않는다(
        String field, String value, String errorCode) throws Exception {
        assertNoContent(putPreferences(token, SAVED_PREFERENCE));
        Map<String, Object> before = storedPreference();
        ObjectNode invalid = (ObjectNode) objectMapper.readTree(SAVED_PREFERENCE);
        if (value == null) {
            invalid.remove(field);
        } else {
            invalid.set(field, objectMapper.readTree(value));
        }

        assertError(putPreferences(token, invalid.toString()), 400, errorCode);
        assertError(putPreferences(jwtService.createServiceAuthToken(otherUserId),
            invalid.toString()), 400, errorCode);

        assertThat(storedPreference()).isEqualTo(before);
        assertPreferenceResponse(getPreferences(token), SAVED_PREFERENCE);
        assertThat(recommendationPreferenceRepository.existsById(otherUserId)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"ONBOARDING", "SUSPENDED", "WITHDRAWN"})
    void ACTIVE가_아니면_조회와_수정을_거절하고_기존_조건을_유지한다(UserStatus status)
        throws Exception {
        assertNoContent(putPreferences(token, SAVED_PREFERENCE));
        Map<String, Object> before = storedPreference();
        jdbcTemplate.update("update users set status = ? where id = ?", status.name(), userId);

        assertError(getPreferences(token), 403, "REQUESTER_NOT_ACTIVE");
        assertError(putPreferences(token, UNRESTRICTED_PREFERENCE), 403, "REQUESTER_NOT_ACTIVE");
        assertThat(storedPreference()).isEqualTo(before);
    }

    @Test
    void 토큰의_회원이_DB에_없으면_조회와_저장을_403으로_거절한다() throws Exception {
        String missingUserToken = jwtService.createServiceAuthToken(Long.MAX_VALUE);

        assertError(getPreferences(missingUserToken), 403, "REQUESTER_NOT_ACTIVE");
        assertError(putPreferences(missingUserToken, SAVED_PREFERENCE), 403, "REQUESTER_NOT_ACTIVE");
        assertThat(recommendationPreferenceRepository.count()).isZero();
    }

    @Test
    void 인증이_없거나_토큰이_잘못되거나_Refresh_토큰이면_401로_거절한다() throws Exception {
        for (String rejectedToken : new String[]{
            null, "invalid-token", jwtService.createRefreshAuthToken(userId)
        }) {
            assertError(getPreferences(rejectedToken), 401, "AUTH_REQUIRED");
            assertError(putPreferences(rejectedToken, SAVED_PREFERENCE), 401, "AUTH_REQUIRED");
        }
        assertThat(recommendationPreferenceRepository.count()).isZero();
    }

    private static Stream<Arguments> invalidRequests() {
        return Stream.of(
            Arguments.of("minAge", "18", "PREFERENCE_AGE_OUT_OF_RANGE"),
            Arguments.of("maxHeight", "221", "PREFERENCE_HEIGHT_OUT_OF_RANGE"),
            Arguments.of("minAge", "31", "PREFERENCE_AGE_RANGE_INVALID"),
            Arguments.of("religion", "[\"NONE\", \"NONE\"]",
                "PREFERENCE_RELIGION_DUPLICATE_VALUE"),
            Arguments.of("drinking", "[null]", "PREFERENCE_DRINKING_NULL_ELEMENT"),
            Arguments.of("religion", "[\"UNKNOWN\"]", "INVALID_REQUEST"),
            Arguments.of("minAge", null, "INVALID_REQUEST"));
    }

    private Long createActiveUser(String name) {
        LocalDateTime now = LocalDateTime.now();
        User user = User.create(name, LocalDate.of(1996, 1, 1), Gender.MALE, now);
        user.activate(now);
        return userRepository.saveAndFlush(user).getId();
    }

    private Map<String, Object> storedPreference() {
        return jdbcTemplate.queryForMap(
            "select * from recommendation_preferences where user_id = ?", userId);
    }

    private Long activeBatchId() {
        return jdbcTemplate.queryForObject("""
            select max(id) from recommendation_batches
            where user_id = ? and deleted_at is null
            """, Long.class, userId);
    }

    private Long batchCount() {
        return jdbcTemplate.queryForObject(
            "select count(*) from recommendation_batches where user_id = ?", Long.class, userId);
    }

    private String activeBatchGenerationType() {
        return jdbcTemplate.queryForObject("""
            select generation_type from recommendation_batches
            where user_id = ? and deleted_at is null
            """, String.class, userId);
    }

    private void assertEmptyJsonArrays() {
        for (String column : new String[]{"religion", "drinking", "smoking"}) {
            assertThat(jdbcTemplate.queryForObject(
                "select JSON_LENGTH(" + column + ") from recommendation_preferences where user_id = ?",
                Integer.class, userId)).isZero();
        }
    }

    private void assertPreferenceResponse(HttpResponse<String> response, String preference) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(objectMapper.readTree(response.body())).isEqualTo(objectMapper.readTree(
            "{\"message\":\"preference_get_success\",\"data\":{\"preference\":" + preference + "}}"));
    }

    private void assertNoContent(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(objectMapper.readTree(response.body())).isEqualTo(objectMapper.readTree(
            "{\"errorCode\":\"" + code + "\"}"));
    }

    private HttpResponse<String> getPreferences(String authToken) throws Exception {
        return httpClient.send(request(authToken).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> putPreferences(String authToken, String body) throws Exception {
        return httpClient.send(request(authToken).header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private CompletableFuture<HttpResponse<String>> putPreferencesAsync(String body) {
        return httpClient.sendAsync(request(token).header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private void executeTriggerSql(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
            MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
            Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void awaitWaitingRequests(Connection observer, int expected) throws Exception {
        String sql = """
            select count(distinct waits.REQUESTING_ENGINE_TRANSACTION_ID)
            from performance_schema.data_lock_waits waits
            join performance_schema.data_locks locks
              on locks.ENGINE = waits.ENGINE
             and locks.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
            where locks.OBJECT_SCHEMA = ?
              and locks.OBJECT_NAME = 'users'
              and locks.INDEX_NAME = 'PRIMARY'
              and locks.LOCK_STATUS = 'WAITING'
              and locks.LOCK_MODE like 'X%'
              and locks.LOCK_DATA = ?
            """;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        int waitingRequests = 0;
        try (PreparedStatement statement = observer.prepareStatement(sql)) {
            statement.setString(1, MYSQL.getDatabaseName());
            statement.setString(2, userId.toString());
            do {
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    waitingRequests = result.getInt(1);
                }
                if (waitingRequests == expected) {
                    return;
                }
                Thread.sleep(25);
            } while (System.nanoTime() < deadline);
        }
        assertThat(waitingRequests).as("회원 행 잠금을 기다리는 HTTP 요청 수").isEqualTo(expected);
    }

    private HttpRequest.Builder request(String authToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + ENDPOINT))
            .timeout(Duration.ofSeconds(10));
        if (authToken != null) {
            builder.header("Authorization", "Bearer " + authToken);
        }
        return builder;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.team.dating_backend")
    @EnableJpaRepositories(
        basePackages = {
            "com.team.dating_backend.user.repository",
            "com.team.dating_backend.recommendation.repository"
        }
    )
    @EnableConfigurationProperties({JwtProperties.class, SecurityProperties.class})
    @Import(
        {
            SecurityConfig.class,
            RecommendationPreferenceController.class,
            RecommendationPreferenceService.class,
            RecommendationBatchCreateService.class,
            GlobalExceptionHandler.class,
            JwtService.class,
            ApiAuthenticationEntryPoint.class,
            ApiAccessDeniedHandler.class
        }
    )
    static class TestApplication {}
}
