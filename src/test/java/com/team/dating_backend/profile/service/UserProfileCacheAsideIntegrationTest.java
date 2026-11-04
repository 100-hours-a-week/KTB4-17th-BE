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
        "jwt.pending-expiration-minutes=5"}
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

    private final List<Boundary> boundaries = new CopyOnWriteArrayList<>();
    private final AtomicInteger signedCount = new AtomicInteger();
    private Long viewerId;
    private Long targetId;
    private Long targetProfileId;
    private String firstStorageKey;
    private Instant imageExpiration;

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
            "com.team.dating_backend.user.repository", "com.team.dating_backend.file.repository"}
    )
    @EnableConfigurationProperties(
        {JwtProperties.class, SecurityProperties.class, S3Properties.class,
            ProfileCacheProperties.class}
    )
    @Import(
        {RedisConfiguration.class, ProfileCacheRedisConfiguration.class, UserProfileCache.class,
            ProfileCacheExpirationPolicy.class, UserProfileGetService.class, UserProfileSourceLoader.class,
            ProfileAvailabilityValidator.class, ProfileImageGetService.class, FileAccessUrlCreateService.class,
            UserProfileController.class, MyProfileController.class, MyProfileGetService.class, SecurityConfig.class,
            JwtService.class, UserAuthenticationService.class, ApiAuthenticationEntryPoint.class,
            ApiAccessDeniedHandler.class, GlobalExceptionHandler.class}
    )
    static class TestApplication {}
}
