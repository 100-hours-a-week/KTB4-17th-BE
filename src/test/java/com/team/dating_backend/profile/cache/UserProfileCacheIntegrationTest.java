package com.team.dating_backend.profile.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import com.team.dating_backend.common.config.RedisConfiguration;
import com.team.dating_backend.profile.config.ProfileCacheRedisConfiguration;
import com.team.dating_backend.profile.dto.response.UserProfileResponse;
import com.team.dating_backend.profile.enums.BodyType;
import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.EducationLevel;
import com.team.dating_backend.profile.enums.JobCategory;
import com.team.dating_backend.profile.enums.Mbti;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class UserProfileCacheIntegrationTest {

    @Container
    static final GenericContainer<?> CHAT = redis();

    @Container
    static final GenericContainer<?> CACHE = redis();

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
            .withUserConfiguration(RedisConfiguration.class, ProfileCacheRedisConfiguration.class,
                UserProfileCache.class)
            .withBean(JsonMapper.class, JsonMapper::new)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("spring.data.redis.url=" + url(CHAT),
                "app.profile-cache.redis.url=" + url(CACHE));
    }

    @Test
    void 기존_Redis에_영향없이_캐시_Redis에_기존_응답_JSON과_TTL을_저장한다() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            UserProfileCache cache = context.getBean(UserProfileCache.class);
            StringRedisTemplate chat = context.getBean("stringRedisTemplate", StringRedisTemplate.class);
            StringRedisTemplate redis = context.getBean("profileCacheStringRedisTemplate", StringRedisTemplate.class);
            UserProfileResponse profile = profile(21L);
            String key = "profile:detail:21";
            chat.opsForValue().set(key, "chat-data");

            cache.put(21L, profile, Instant.now().plusSeconds(180));

            assertThat(cache.get(21L).profile()).isEqualTo(profile);
            assertThat(chat.opsForValue().get(key)).isEqualTo("chat-data");
            assertThat(redis.opsForValue().get(key))
                .isEqualTo(context.getBean(JsonMapper.class).writeValueAsString(profile));
            assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(180_000L);
        });
    }

    @Test
    void 일반_조회는_TTL을_연장하지_않고_만료_후에는_미적중을_반환한다() {
        runner().run(context -> {
            UserProfileCache cache = context.getBean(UserProfileCache.class);
            StringRedisTemplate redis = context.getBean("profileCacheStringRedisTemplate", StringRedisTemplate.class);
            String key = "profile:detail:22";
            cache.put(22L, profile(22L), Instant.now().plusSeconds(3));
            long initialTtl = redis.getExpire(key, TimeUnit.MILLISECONDS);
            await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive()
                    .isLessThan(initialTtl - 200));
            long beforeRead = redis.getExpire(key, TimeUnit.MILLISECONDS);

            assertThat(cache.get(22L).status()).isEqualTo(ProfileCacheLookupResult.Status.HIT);

            assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(beforeRead);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(
                () -> assertThat(cache.get(22L).status()).isEqualTo(ProfileCacheLookupResult.Status.MISS));
        });
    }

    @Test
    void 손상된_JSON을_삭제없이_정상_응답과_새_TTL로_덮어쓸_수_있다() {
        runner().run(context -> {
            UserProfileCache cache = context.getBean(UserProfileCache.class);
            StringRedisTemplate redis = context.getBean("profileCacheStringRedisTemplate", StringRedisTemplate.class);
            String key = "profile:detail:23";
            redis.opsForValue().set(key, "{broken-json", Duration.ofMinutes(10));

            assertThat(cache.get(23L).status()).isEqualTo(ProfileCacheLookupResult.Status.INVALID);
            assertThat(redis.opsForValue().get(key)).isEqualTo("{broken-json");
            cache.put(23L, profile(23L), Instant.now().plusSeconds(30));

            assertThat(cache.get(23L).profile()).isEqualTo(profile(23L));
            assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(30_000L);
        });
    }

    @Test
    void 삭제를_반복해도_정상_종료하고_이후_조회는_미적중이다() {
        runner().run(context -> {
            UserProfileCache cache = context.getBean(UserProfileCache.class);
            cache.put(24L, profile(24L), Instant.now().plusSeconds(180));

            cache.evict(24L);
            cache.evict(24L);

            assertThat(cache.get(24L).status()).isEqualTo(ProfileCacheLookupResult.Status.MISS);
            MeterRegistry metrics = context.getBean(MeterRegistry.class);
            assertThat(metrics.get("profile.cache.evict.requests").tag("result", "deleted").counter().count())
                .isEqualTo(1);
            assertThat(metrics.get("profile.cache.evict.requests").tag("result", "absent").counter().count())
                .isEqualTo(1);
        });
    }

    @Test
    void 기한이_지난_저장_요청은_기존_캐시와_TTL을_변경하지_않는다() {
        runner().run(context -> {
            UserProfileCache cache = context.getBean(UserProfileCache.class);
            StringRedisTemplate redis = context.getBean("profileCacheStringRedisTemplate", StringRedisTemplate.class);
            String key = "profile:detail:25";
            redis.opsForValue().set(key, "existing-value", Duration.ofSeconds(30));
            long before = redis.getExpire(key, TimeUnit.MILLISECONDS);

            cache.put(25L, profile(25L), Instant.now().minusSeconds(1));

            assertThat(redis.opsForValue().get(key)).isEqualTo("existing-value");
            assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(before);
        });
    }

    @Test
    void 캐시_Redis_접근_실패는_결과와_메트릭으로_관찰한다() {
        runner().withPropertyValues("app.profile-cache.redis.url=redis://127.0.0.1:1").run(context -> {
            assertThat(context).hasNotFailed();
            UserProfileCache cache = context.getBean(UserProfileCache.class);

            assertThat(cache.get(26L).status()).isEqualTo(ProfileCacheLookupResult.Status.READ_ERROR);
            assertThatCode(() -> cache.put(26L, profile(26L), Instant.now().plusSeconds(180)))
                .doesNotThrowAnyException();
            assertThatCode(() -> cache.evict(26L)).doesNotThrowAnyException();

            MeterRegistry metrics = context.getBean(MeterRegistry.class);
            assertThat(metrics.get("profile.cache.get.requests").tag("result", "read_error").counter().count())
                .isEqualTo(1);
            assertThat(metrics.get("profile.cache.put.requests").tag("result", "write_error").counter().count())
                .isEqualTo(1);
            assertThat(metrics.get("profile.cache.evict.requests").tag("result", "delete_error").counter().count())
                .isEqualTo(1);
        });
    }

    private static GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    }

    private static String url(GenericContainer<?> redis) {
        return "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
    }

    private static UserProfileResponse profile(Long userId) {
        return new UserProfileResponse(userId, "회원", 29, JobCategory.IT_DATA, "서울특별시 강남구", (short) 175,
            BodyType.AVERAGE, EducationLevel.BACHELOR, Religion.NONE, Drinking.SOCIAL, Smoking.NON_SMOKER,
            Mbti.INTJ, List.of(new UserProfileResponse.Image(701L, (short) 1, "https://example.com/701")));
    }
}
