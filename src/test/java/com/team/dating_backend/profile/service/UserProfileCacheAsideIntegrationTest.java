package com.team.dating_backend.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.auth.config.AuthWebProperties;
import com.team.dating_backend.auth.controller.AuthCookieFactory;
import com.team.dating_backend.auth.service.AuthTokenService;
import com.team.dating_backend.aipractice.service.AiPracticeWithdrawalTerminationService;
import com.team.dating_backend.aisimulation.service.AiSimulationWithdrawalTerminationService;
import com.team.dating_backend.chat.service.ChatRoomWithdrawalTerminationService;
import com.team.dating_backend.file.repository.JpaFileRepositoryAdapter;
import com.team.dating_backend.matching.service.LikeTerminationService;
import com.team.dating_backend.matching.service.MatchWithdrawalTerminationService;
import com.team.dating_backend.onboarding.controller.ProfileSaveController;
import com.team.dating_backend.onboarding.dto.request.ProfileSaveRequest;
import com.team.dating_backend.onboarding.service.OnboardingCompletionService;
import com.team.dating_backend.onboarding.service.ProfileSaveService;
import com.team.dating_backend.persona.client.PersonaAiClient;
import com.team.dating_backend.profile.cache.ProfileCacheEvictionListener;
import com.team.dating_backend.profile.controller.ProfileImageController;
import com.team.dating_backend.profile.dto.event.ProfileCacheEvictionRequestedEvent;
import com.team.dating_backend.profile.dto.request.ProfileImageSaveItemRequest;
import com.team.dating_backend.profile.dto.request.ProfileImageSaveRequest;
import com.team.dating_backend.profile.enums.JobCategory;
import com.team.dating_backend.user.controller.UserWithdrawalController;
import com.team.dating_backend.user.service.UserWithdrawalService;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import org.hibernate.SessionFactory;
import com.team.dating_backend.TestJwtSecret;
import com.team.dating_backend.activityregion.entity.ActivityRegion;
import com.team.dating_backend.auth.config.JwtProperties;
import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.common.config.RedisConfiguration;
import com.team.dating_backend.common.exception.GlobalExceptionHandler;
import com.team.dating_backend.file.config.S3Properties;
import com.team.dating_backend.file.entity.File;
import com.team.dating_backend.file.service.FileAccessUrlCreateService;
import com.team.dating_backend.file.storage.FileStorage;
import com.team.dating_backend.file.storage.PresignedReadUrl;
import com.team.dating_backend.profile.cache.ProfileCacheExpirationPolicy;
import com.team.dating_backend.profile.cache.UserProfileCache;
import com.team.dating_backend.profile.config.ProfileCacheProperties;
import com.team.dating_backend.profile.config.ProfileCacheRedisConfiguration;
import com.team.dating_backend.profile.controller.MyProfileController;
import com.team.dating_backend.profile.controller.UserProfileController;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.entity.ProfileImage;
import com.team.dating_backend.profile.enums.ProfileCacheErrorCode;
import com.team.dating_backend.security.ApiAccessDeniedHandler;
import com.team.dating_backend.security.ApiAuthenticationEntryPoint;
import com.team.dating_backend.security.UserAuthenticationService;
import com.team.dating_backend.security.config.SecurityConfig;
import com.team.dating_backend.security.config.SecurityProperties;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.enums.UserStatus;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    classes = UserProfileCacheAsideIntegrationTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.config.name=profile-cache-flow-test", "server.address=127.0.0.1",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.format_sql=false",
        "spring.flyway.enabled=true", "sentry.dsn=", "sentry.enabled=false",
        "app.security.allowed-origins=http://localhost", "app.file.s3.bucket=cache-test",
        "app.file.s3.region=ap-northeast-2", "app.file.s3.upload-url-expiration=5m",
        "app.file.s3.download-url-expiration=10m", "jwt.service-expiration-minutes=60",
        "jwt.pending-expiration-minutes=5", "app.auth.secure-cookie=false", "app.auth.same-site=Lax"}
)
@ContextConfiguration(initializers = UserProfileCacheAsideIntegrationTest.MainYamlInitializer.class)
@Testcontainers
class UserProfileCacheAsideIntegrationTest {

    private static final String JWT_SECRET = TestJwtSecret.generate();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.url", UserProfileCacheAsideIntegrationTest::redisUrl);
        registry.add("app.profile-cache.redis.url", UserProfileCacheAsideIntegrationTest::redisUrl);
        registry.add("jwt.secret", () -> JWT_SECRET);
    }

    @LocalServerPort
    private int port;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtService jwt;
    @Autowired
    private JsonMapper json;
    @Autowired
    private MeterRegistry metrics;
    @Autowired
    private ProfileCacheProperties cacheProperties;
    @Autowired
    private ConfigurableApplicationContext context;

    @MockitoBean
    private FileStorage fileStorage;
    @MockitoSpyBean
    private UserProfileCache cache;
    @MockitoSpyBean(name = "profileCacheStringRedisTemplate")
    private StringRedisTemplate redis;

    @Autowired
    private ProfileSaveService profileSaveService;
    @Autowired
    private ProfileImageSaveService profileImageSaveService;
    @Autowired
    private UserWithdrawalService userWithdrawalService;
    @MockitoBean
    private OnboardingCompletionService onboardingCompletionService;
    @MockitoBean
    private PersonaAiClient personaAiClient;
    @MockitoBean
    private AuthTokenService authTokenService;
    @MockitoBean
    private LikeTerminationService likeTerminationService;
    @MockitoBean
    private MatchWithdrawalTerminationService matchWithdrawalTerminationService;
    @MockitoBean
    private ChatRoomWithdrawalTerminationService chatRoomWithdrawalTerminationService;
    @MockitoBean
    private AiPracticeWithdrawalTerminationService aiPracticeWithdrawalTerminationService;
    @MockitoBean
    private AiSimulationWithdrawalTerminationService aiSimulationWithdrawalTerminationService;

    private final List<Boundary> boundaries = new CopyOnWriteArrayList<>();
    private final AtomicInteger signedCount = new AtomicInteger();
    private Long viewerId;
    private Long targetId;
    private Long targetProfileId;
    private String firstStorageKey;
    private Instant imageExpiration;
    private Long replacementFileId;

    @BeforeEach
    void setUp() {
        reset(cache, redis, fileStorage);
        cacheProperties.setEnabled(true);
        signedCount.set(0);
        imageExpiration = Instant.now().plusSeconds(600);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now();
            ActivityRegion region = new ActivityRegion("CACHE_" + System.nanoTime(), "서울특별시", "강남구",
                BigDecimal.valueOf(37), BigDecimal.valueOf(127));
            entityManager.persist(region);
            User viewer = activeUser(now);
            Profile viewerProfile = profile(viewer, region);
            User target = activeUser(now);
            Profile targetProfile = profile(target, region);
            viewerId = viewer.getId();
            targetId = target.getId();
            targetProfileId = targetProfile.getId();
            firstStorageKey = "cache-image-" + targetId;
            addImage(targetProfile, firstStorageKey, (short) 1);
            File replacement = File.create(targetId, "replacement-image-" + targetId,
                "replacement.png", "image/png", 100L);
            entityManager.persist(replacement);
            replacementFileId = replacement.getId();
            entityManager.flush();
        });
        given(fileStorage.createReadUrl(anyString(), anyString(), anyString(), anyString(), any(Duration.class)))
            .willAnswer(invocation -> new PresignedReadUrl(
                "https://example.com/image-" + signedCount.incrementAndGet(), imageExpiration));
        doAnswer(invocation -> {
            boundary("get");
            return invocation.callRealMethod();
        }).when(cache).get(anyLong());
        doAnswer(invocation -> {
            boundary("put");
            return invocation.callRealMethod();
        }).when(cache).put(anyLong(), any(), any());
        boundaries.clear();
        clearInvocations(cache, redis);
    }

    @Test
    void 미적중_후_적중하며_Redis_접근시_DB_트랜잭션과_연결을_유지하지_않는다() throws Exception {
        assertThat(context.getEnvironment().getProperty("spring.jpa.open-in-view", Boolean.class)).isFalse();
        assertThat(context.getBeansOfType(OpenEntityManagerInViewInterceptor.class)).isEmpty();
        HttpResponse<String> first = get(detailPath(), true);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");
        JsonNode data = body(first).path("data");
        assertThat(data.path("userId").asLong()).isEqualTo(targetId);
        assertThat(data.path("region").asText()).isEqualTo("서울특별시 강남구");
        assertThat(data.path("age").asInt()).isEqualTo(29);
        assertThat(data.path("images").get(0).has("expiresAt")).isFalse();
        assertThat(redis.getExpire(key(), java.util.concurrent.TimeUnit.MILLISECONDS)).isPositive()
            .isLessThanOrEqualTo(180_000L);

        HttpResponse<String> second = get(detailPath(), true);

        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(body(second)).isEqualTo(body(first));
        assertThat(signedCount).hasValue(1);
        assertThat(boundaries).extracting(Boundary::operation).containsExactly("get", "put", "get");
        assertThat(boundaries).allSatisfy(sample -> {
            assertThat(sample.transactionActive()).isFalse();
            assertThat(sample.entityManagerBound()).isFalse();
            assertThat(sample.activeConnections()).isZero();
        });
    }

    @Test
    void 캐시_적중은_미적중보다_SQL이_적고_본문과_이미지_URL을_다시_만들지_않는다() throws Exception {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean previouslyEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            HttpResponse<String> miss = get(detailPath(), true);
            assertThat(miss.statusCode()).isEqualTo(200);
            long missStatements = statistics.getPrepareStatementCount();
            statistics.clear();

            HttpResponse<String> hit = get(detailPath(), true);

            long hitStatements = statistics.getPrepareStatementCount();
            assertThat(hit.statusCode()).isEqualTo(200);
            assertThat(body(hit)).isEqualTo(body(miss));
            assertThat(hitStatements).isPositive().isLessThan(missStatements);
            assertThat(signedCount).hasValue(1);
            System.out.printf("PROFILE_CACHE_SQL miss=%d hit=%d%n", missStatements, hitStatements);
        } finally {
            statistics.setStatisticsEnabled(previouslyEnabled);
            statistics.clear();
        }
    }

    @Test
    void 서로_다른_조회자가_같은_대상의_공통_캐시를_재사용한다() throws Exception {
        HttpResponse<String> first = get(detailPath(), true);
        assertThat(first.statusCode()).isEqualTo(200);
        String cached = redis.opsForValue().get(key());
        double hitsBefore = count("get", "hit");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + detailPath()))
            .timeout(Duration.ofSeconds(10)).GET()
            .header("Authorization", "Bearer " + jwt.createServiceAuthToken(targetId)).build();

        HttpResponse<String> second = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(body(second)).isEqualTo(body(first));
        assertThat(count("get", "hit")).isEqualTo(hitsBefore + 1);
        assertThat(redis.opsForValue().get(key())).isEqualTo(cached);
        assertThat(signedCount).hasValue(1);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"ONBOARDING", "SUSPENDED", "WITHDRAWN"})
    void 캐시가_있어도_비활성_대상은_조회할_수_없다(UserStatus status) throws Exception {
        assertThat(get(detailPath(), true).statusCode()).isEqualTo(200);
        boundaries.clear();
        signedCount.set(0);
        jdbc.update("UPDATE users SET status = ? WHERE id = ?", status.name(), targetId);

        HttpResponse<String> response = get(detailPath(), true);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(body(response).path("errorCode").asText()).isEqualTo("PROFILE_NOT_FOUND");
        assertThat(boundaries).isEmpty();
        assertThat(signedCount).hasValue(0);
    }

    @Test
    void 캐시가_있어도_삭제된_프로필은_조회할_수_없다() throws Exception {
        assertThat(get(detailPath(), true).statusCode()).isEqualTo(200);
        boundaries.clear();
        jdbc.update("UPDATE profiles SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", targetProfileId);

        assertThat(get(detailPath(), true).statusCode()).isEqualTo(404);
        assertThat(boundaries).isEmpty();
    }

    @Test
    void 손상된_캐시는_DB_결과로_교체한다() throws Exception {
        redis.opsForValue().set(key(), "{broken", Duration.ofMinutes(3));
        double before = count("get", "invalid");

        assertThat(get(detailPath(), true).statusCode()).isEqualTo(200);
        assertThat(get(detailPath(), true).statusCode()).isEqualTo(200);

        assertThat(count("get", "invalid")).isEqualTo(before + 1);
        assertThat(signedCount).hasValue(1);
        assertThat(json.readValue(redis.opsForValue().get(key()),
            com.team.dating_backend.profile.dto.response.UserProfileResponse.class).userId()).isEqualTo(targetId);
    }

    @Test
    void 캐시를_끄면_기존_캐시를_읽거나_덮어쓰지_않는다() throws Exception {
        cacheProperties.setEnabled(false);
        redis.opsForValue().set(key(), "existing-cache", Duration.ofMinutes(3));

        assertThat(get(detailPath(), true).statusCode()).isEqualTo(200);
        assertThat(get(detailPath(), true).statusCode()).isEqualTo(200);

        assertThat(boundaries).isEmpty();
        assertThat(signedCount).hasValue(2);
        assertThat(redis.opsForValue().get(key())).isEqualTo("existing-cache");
    }

    @Test
    void 읽기_실패_요청은_저장하지_않고_다음_요청은_캐시를_다시_시도한다() throws Exception {
        doThrow(new RedisConnectionFailureException(ProfileCacheErrorCode.PROFILE_CACHE_READ_FAILED.name()))
            .when(redis).opsForValue();
        double before = count("get", "read_error");

        assertThat(get(detailPath(), true).statusCode()).isEqualTo(200);

        verify(cache, never()).put(anyLong(), any(), any());
        assertThat(count("get", "read_error")).isEqualTo(before + 1);
        doCallRealMethod().when(redis).opsForValue();
        assertThat(get(detailPath(), true).statusCode()).isEqualTo(200);
        assertThat(boundaries).extracting(Boundary::operation).containsExactly("get", "get", "put");
        assertThat(signedCount).hasValue(2);
    }

    @Test
    void 쓰기_실패여도_DB에서_만든_정상_응답을_반환한다() throws Exception {
        ValueOperations<String, String> values = spy(redis.opsForValue());
        doThrow(new RedisConnectionFailureException(ProfileCacheErrorCode.PROFILE_CACHE_WRITE_FAILED.name()))
            .when(values).set(anyString(), anyString(), any(Duration.class));
        doReturn(values).when(redis).opsForValue();
        double before = count("put", "write_error");

        HttpResponse<String> response = get(detailPath(), true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).path("data").path("images").get(0).path("imageUrl").asText())
            .isEqualTo("https://example.com/image-1");
        assertThat(count("put", "write_error")).isEqualTo(before + 1);
        assertThat(redis.hasKey(key())).isFalse();
    }

    @Test
    void 여러_이미지_중_가장_빠른_만료에서_10초를_뺀_기한으로_저장한다() throws Exception {
        new TransactionTemplate(transactions)
            .executeWithoutResult(status -> addImage(entityManager.getReference(Profile.class, targetProfileId),
                "short-image-" + targetId, (short) 2));
        Instant earlyExpiration = Instant.now().plusSeconds(120);
        given(fileStorage.createReadUrl(anyString(), anyString(), anyString(), anyString(), any(Duration.class)))
            .willAnswer(invocation -> new PresignedReadUrl("https://example.com/image-" + signedCount.incrementAndGet(),
                invocation.getArgument(0).equals(firstStorageKey) ? imageExpiration : earlyExpiration));

        assertThat(get(detailPath(), true).statusCode()).isEqualTo(200);

        assertThat(redis.getExpire(key(), java.util.concurrent.TimeUnit.MILLISECONDS)).isBetween(100_000L, 110_000L);
        assertThat(signedCount).hasValue(2);
    }

    @Test
    void 이미지가_없으면_최대_TTL로_저장한다() throws Exception {
        jdbc.update("UPDATE profile_images SET deleted_at = CURRENT_TIMESTAMP WHERE profile_id = ?", targetProfileId);

        HttpResponse<String> response = get(detailPath(), true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).path("data").path("images").isEmpty()).isTrue();
        assertThat(redis.getExpire(key(), java.util.concurrent.TimeUnit.MILLISECONDS)).isBetween(170_000L, 180_000L);
        assertThat(signedCount).hasValue(0);
    }

    @Test
    void 이미지_요청_여유가_남지_않으면_응답만_반환하고_저장을_생략한다() throws Exception {
        imageExpiration = Instant.now().plusSeconds(5);
        double before = count("put", "expired");

        assertThat(get(detailPath(), true).statusCode()).isEqualTo(200);

        assertThat(redis.hasKey(key())).isFalse();
        assertThat(count("put", "expired")).isEqualTo(before + 1);
    }

    @Test
    void 이미지_만료정보가_없으면_응답만_반환하고_저장을_생략한다() throws Exception {
        imageExpiration = null;

        assertThat(get(detailPath(), true).statusCode()).isEqualTo(200);

        assertThat(redis.hasKey(key())).isFalse();
        assertThat(boundaries).extracting(Boundary::operation).containsExactly("get");
    }

    @Test
    void 인증이_없으면_캐시에_접근하지_않는다() throws Exception {
        assertThat(get(detailPath(), false).statusCode()).isEqualTo(401);
        assertThat(boundaries).isEmpty();
    }

    @Test
    void OSIV가_꺼져도_내_프로필은_생일과_지역을_포함해_직렬화된다() throws Exception {
        HttpResponse<String> response = get("/api/v1/users/me/profile", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).path("data").path("birthDate").asText())
            .isEqualTo(LocalDate.now().minusYears(29).toString());
        assertThat(body(response).path("data").path("activityRegionName").asText())
            .isEqualTo("서울특별시 강남구");
        assertThat(boundaries).isEmpty();
    }

    @Test
    void 프로필_수정_HTTP_커밋_후_본인_캐시만_삭제하고_다음_조회에_변경을_반영한다() throws Exception {
        HttpResponse<String> original = get(detailPath(), true);
        assertThat(original.statusCode()).isEqualTo(200);
        String otherKey = "profile:detail:" + viewerId;
        assertThat(get("/api/v1/users/" + viewerId + "/profile", true).statusCode()).isEqualTo(200);
        String otherCache = redis.opsForValue().get(otherKey);

        assertThat(mutateHttp(Mutation.PROFILE).statusCode()).isEqualTo(204);

        assertThat(redis.hasKey(key())).isFalse();
        assertThat(redis.opsForValue().get(otherKey)).isEqualTo(otherCache);
        HttpResponse<String> refreshed = get(detailPath(), true);
        assertThat(refreshed.statusCode()).isEqualTo(200);
        assertThat(body(refreshed).path("data").path("nickname").asText()).isEqualTo(changedNickname());
        assertThat(signedCount).hasValue(2);
        assertThat(body(get(detailPath(), true))).isEqualTo(body(refreshed));
        assertThat(signedCount).hasValue(2);
    }

    @Test
    void 이미지_추가_제거_순서변경_HTTP_커밋_후_캐시를_삭제하고_새_목록을_반환한다() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(
            status -> addImage(entityManager.getReference(Profile.class, targetProfileId), "second-image-" + targetId,
                (short) 2));
        List<Long> existing = jdbc.queryForList(
            "SELECT image_id FROM profile_images WHERE profile_id = ? ORDER BY display_order", Long.class,
            targetProfileId);
        assertThat(get(detailPath(), true).statusCode()).isEqualTo(200);
        ProfileImageSaveRequest request = new ProfileImageSaveRequest(List.of(
            new ProfileImageSaveItemRequest(existing.get(1), true),
            new ProfileImageSaveItemRequest(replacementFileId, false)));

        assertThat(write("PUT", "/api/v1/users/me/profile/images", json.writeValueAsString(request))
            .statusCode()).isEqualTo(200);

        assertThat(redis.hasKey(key())).isFalse();
        JsonNode images = body(get(detailPath(), true)).path("data").path("images");
        assertThat(images.size()).isEqualTo(2);
        assertThat(images.get(0).path("fileId").asLong()).isEqualTo(existing.get(1));
        assertThat(images.get(0).path("displayOrder").asInt()).isEqualTo(1);
        assertThat(images.get(1).path("fileId").asLong()).isEqualTo(replacementFileId);
        assertThat(jdbc.queryForObject(
            "SELECT deleted_at IS NOT NULL FROM profile_images WHERE profile_id = ? AND image_id = ?",
            Boolean.class, targetProfileId, existing.get(0))).isTrue();
    }

    @Test
    void 탈퇴_HTTP_커밋_후_캐시를_삭제하고_프로필_상세_조회가_제한된다() throws Exception {
        assertThat(get(detailPath(), true).statusCode()).isEqualTo(200);

        assertThat(mutateHttp(Mutation.WITHDRAWAL).statusCode()).isEqualTo(204);

        assertThat(redis.hasKey(key())).isFalse();
        assertCommitted(Mutation.WITHDRAWAL);
        assertThat(get(detailPath(), true).statusCode()).isEqualTo(404);
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void 변경_서비스가_반환해도_외부_트랜잭션_커밋_전에는_캐시를_삭제하지_않는다(Mutation mutation) throws Exception {
        assertThat(get(detailPath(), true).statusCode()).isEqualTo(200);
        String original = redis.opsForValue().get(key());

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            mutate(mutation);
            entityManager.flush();
            assertThat(redis.opsForValue().get(key())).isEqualTo(original);
            verify(cache, never()).evict(targetId);
        });

        assertThat(redis.hasKey(key())).isFalse();
        verify(cache).evict(targetId);
        assertCommitted(mutation);
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void 변경_후_트랜잭션이_롤백되면_DB와_기존_캐시를_유지한다(Mutation mutation) throws Exception {
        assertThat(get(detailPath(), true).statusCode()).isEqualTo(200);
        String original = redis.opsForValue().get(key());
        List<Long> originalImages = activeImageIds();

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            mutate(mutation);
            entityManager.flush();
            assertThat(redis.opsForValue().get(key())).isEqualTo(original);
            status.setRollbackOnly();
        });

        assertThat(redis.opsForValue().get(key())).isEqualTo(original);
        verify(cache, never()).evict(targetId);
        assertThat(jdbc.queryForObject("SELECT nickname FROM profiles WHERE id = ?", String.class,
            targetProfileId)).isEqualTo("회원" + targetId);
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, targetId))
            .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM profiles WHERE id = ?", Boolean.class,
            targetProfileId)).isTrue();
        assertThat(activeImageIds()).isEqualTo(originalImages);
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    @ExtendWith(OutputCaptureExtension.class)
    void 캐시_삭제가_실패해도_HTTP_성공과_DB_변경을_유지하고_오류를_기록한다(
        Mutation mutation, CapturedOutput output) throws Exception {
        assertThat(get(detailPath(), true).statusCode()).isEqualTo(200);
        String original = redis.opsForValue().get(key());
        double before = count("evict", "delete_error");
        doThrow(new RedisConnectionFailureException(ProfileCacheErrorCode.PROFILE_CACHE_DELETE_FAILED.name()))
            .when(redis).delete(key());

        HttpResponse<String> response = mutateHttp(mutation);

        assertThat(response.statusCode()).isEqualTo(mutation == Mutation.IMAGES ? 200 : 204);
        assertCommitted(mutation);
        assertThat(redis.opsForValue().get(key())).isEqualTo(original);
        assertThat(redis.getExpire(key(), java.util.concurrent.TimeUnit.MILLISECONDS)).isPositive()
            .isLessThanOrEqualTo(180_000L);
        assertThat(count("evict", "delete_error")).isEqualTo(before + 1);
        assertThat(output.getOut()).contains("errorCode=PROFILE_CACHE_DELETE_FAILED", "operation=evict");
        if (mutation == Mutation.WITHDRAWAL) {
            assertThat(get(detailPath(), true).statusCode()).isEqualTo(404);
        }
    }

    @Test
    void 삭제할_캐시가_없어도_프로필_수정은_성공한다() throws Exception {
        double before = count("evict", "absent");

        assertThat(mutateHttp(Mutation.PROFILE).statusCode()).isEqualTo(204);

        assertCommitted(Mutation.PROFILE);
        assertThat(count("evict", "absent")).isEqualTo(before + 1);
    }

    @Test
    void 트랜잭션_밖에서_발행한_이벤트는_캐시를_삭제하지_않는다() throws Exception {
        assertThat(get(detailPath(), true).statusCode()).isEqualTo(200);
        String original = redis.opsForValue().get(key());

        context.publishEvent(new ProfileCacheEvictionRequestedEvent(targetId));

        assertThat(redis.opsForValue().get(key())).isEqualTo(original);
        verify(cache, never()).evict(targetId);
    }

    @Test
    void 조회_캐시를_비활성화해도_수정시_기존_캐시는_삭제한다() throws Exception {
        assertThat(get(detailPath(), true).statusCode()).isEqualTo(200);
        cacheProperties.setEnabled(false);

        assertThat(mutateHttp(Mutation.PROFILE).statusCode()).isEqualTo(204);

        assertThat(redis.hasKey(key())).isFalse();
    }

    private void mutate(Mutation mutation) {
        switch (mutation) {
            case PROFILE -> profileSaveService.saveProfile(targetId, profileRequest());
            case IMAGES -> profileImageSaveService.saveProfileImages(targetId, imageRequest());
            case WITHDRAWAL -> userWithdrawalService.withdraw(targetId);
        }
    }

    private HttpResponse<String> mutateHttp(Mutation mutation) throws Exception {
        return switch (mutation) {
            case PROFILE -> write("PUT", "/api/v1/users/me/profile", json.writeValueAsString(profileRequest()));
            case IMAGES -> write("PUT", "/api/v1/users/me/profile/images", json.writeValueAsString(imageRequest()));
            case WITHDRAWAL -> write("DELETE", "/api/v1/users/me", null);
        };
    }

    private ProfileSaveRequest profileRequest() {
        return new ProfileSaveRequest(changedNickname(), null, null, null, null, JobCategory.IT_DATA,
            null, null, null, null);
    }

    private ProfileImageSaveRequest imageRequest() {
        return new ProfileImageSaveRequest(List.of(new ProfileImageSaveItemRequest(replacementFileId, true)));
    }

    private String changedNickname() {
        return "변경" + targetId;
    }

    private List<Long> activeImageIds() {
        return jdbc.queryForList(
            "SELECT image_id FROM profile_images WHERE profile_id = ? AND deleted_at IS NULL ORDER BY display_order",
            Long.class, targetProfileId);
    }

    private void assertCommitted(Mutation mutation) {
        switch (mutation) {
            case PROFILE -> assertThat(jdbc.queryForObject("SELECT nickname FROM profiles WHERE id = ?",
                String.class, targetProfileId)).isEqualTo(changedNickname());
            case IMAGES -> assertThat(activeImageIds()).containsExactly(replacementFileId);
            case WITHDRAWAL -> {
                assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, targetId))
                    .isEqualTo("WITHDRAWN");
                assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM profiles WHERE id = ?",
                    Boolean.class, targetProfileId)).isTrue();
                assertThat(activeImageIds()).isEmpty();
            }
        }
    }

    private HttpResponse<String> write(String method, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + jwt.createServiceAuthToken(targetId))
            .header("Content-Type", "application/json")
            .method(method,
                body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
            .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    enum Mutation {
        PROFILE, IMAGES, WITHDRAWAL
    }

    private void boundary(String operation) {
        boundaries.add(new Boundary(operation, TransactionSynchronizationManager.isActualTransactionActive(),
            TransactionSynchronizationManager.hasResource(entityManagerFactory),
            ((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections()));
    }

    private HttpResponse<String> get(String path, boolean authenticated) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10)).GET();
        if (authenticated) {
            request.header("Authorization", "Bearer " + jwt.createServiceAuthToken(viewerId));
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    private String detailPath() {
        return "/api/v1/users/" + targetId + "/profile";
    }

    private String key() {
        return "profile:detail:" + targetId;
    }

    private double count(String operation, String result) {
        Counter counter = metrics.find("profile.cache." + operation + ".requests").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private User activeUser(LocalDateTime now) {
        User user = User.create("회원", LocalDate.now().minusYears(29), Gender.MALE, now);
        user.activate(now);
        entityManager.persist(user);
        return user;
    }

    private Profile profile(User user, ActivityRegion region) {
        Profile profile = new Profile(user);
        profile.updateProfile(region, "회원" + user.getId(), null, null, null, null, null, null, null, null);
        entityManager.persist(profile);
        return profile;
    }

    private void addImage(Profile profile, String storageKey, short order) {
        File file = File.create(profile.getUser().getId(), storageKey, "image.png", "image/png", 100L);
        entityManager.persist(file);
        entityManager.persist(new ProfileImage(profile, file, order, order == 1));
    }

    private static String redisUrl() {
        return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
    }

    record Boundary(String operation, boolean transactionActive, boolean entityManagerBound, int activeConnections) {}

    static class MainYamlInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            try {
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
                    .forEach(applicationContext.getEnvironment().getPropertySources()::addLast);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.team.dating_backend")
    @EnableJpaRepositories(
        basePackages = {"com.team.dating_backend.profile.repository",
            "com.team.dating_backend.user.repository", "com.team.dating_backend.file.repository",
            "com.team.dating_backend.activityregion.repository", "com.team.dating_backend.auth.repository"}
    )
    @EnableConfigurationProperties(
        {JwtProperties.class, AuthWebProperties.class, SecurityProperties.class, S3Properties.class,
            ProfileCacheProperties.class}
    )
    @Import(
        {RedisConfiguration.class, ProfileCacheRedisConfiguration.class, UserProfileCache.class,
            ProfileCacheEvictionListener.class, ProfileSaveService.class, ProfileImageSaveService.class,
            UserWithdrawalService.class, ProfileSaveController.class, ProfileImageController.class,
            UserWithdrawalController.class, AuthCookieFactory.class, JpaFileRepositoryAdapter.class,
            ProfileCacheExpirationPolicy.class, UserProfileGetService.class, UserProfileSourceLoader.class,
            ProfileAvailabilityValidator.class, ProfileImageGetService.class, FileAccessUrlCreateService.class,
            UserProfileController.class, MyProfileController.class, MyProfileGetService.class, SecurityConfig.class,
            JwtService.class, UserAuthenticationService.class, ApiAuthenticationEntryPoint.class,
            ApiAccessDeniedHandler.class, GlobalExceptionHandler.class}
    )
    static class TestApplication {}
}
