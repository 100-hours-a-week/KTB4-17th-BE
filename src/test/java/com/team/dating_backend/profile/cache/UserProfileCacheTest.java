package com.team.dating_backend.profile.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.profile.dto.response.UserProfileResponse;
import com.team.dating_backend.profile.enums.ProfileCacheErrorCode;
import com.team.dating_backend.profile.exception.ProfileCacheBusinessException;
import com.team.dating_backend.profile.enums.BodyType;
import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.EducationLevel;
import com.team.dating_backend.profile.enums.JobCategory;
import com.team.dating_backend.profile.enums.Mbti;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class UserProfileCacheTest {

    private static final Long USER_ID = 21L;
    private static final String KEY = "profile:detail:21";
    private static final Instant NOW = Instant.parse("2026-11-04T03:00:00Z");

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> values;
    private JsonMapper jsonMapper;
    private SimpleMeterRegistry meterRegistry;
    private Clock clock;
    private UserProfileCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(values);
        jsonMapper = new JsonMapper();
        meterRegistry = new SimpleMeterRegistry();
        clock = mock(Clock.class);
        given(clock.instant()).willReturn(NOW);
        cache = new UserProfileCache(redisTemplate, jsonMapper, meterRegistry, clock);
    }

    @AfterEach
    void tearDown() {
        meterRegistry.close();
    }

    @Test
    void JSON을_기존_프로필_응답으로_복원하고_적중을_기록한다() {
        UserProfileResponse profile = profile();
        given(values.get(KEY)).willReturn(jsonMapper.writeValueAsString(profile));

        ProfileCacheLookupResult result = cache.get(USER_ID);

        assertThat(result.status()).isEqualTo(ProfileCacheLookupResult.Status.HIT);
        assertThat(result.profile()).isEqualTo(profile);
        assertOutcome("get", "hit");
    }

    @Test
    void 키가_없으면_빈_결과와_미적중을_반환한다() {
        ProfileCacheLookupResult result = cache.get(USER_ID);

        assertThat(result.status()).isEqualTo(ProfileCacheLookupResult.Status.MISS);
        assertThat(result.profile()).isNull();
        assertOutcome("get", "miss");
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"{", "", "null", "{}", "123", "{\"userId\":22,\"images\":[]}",
            "{\"userId\":21,\"images\":null}", "{\"userId\":21,\"images\":[null]}",
            "{\"userId\":21,\"images\":[],\"job\":\"UNKNOWN_JOB\"}"}
    )
    void 손상된_캐시는_미적중과_구분하고_조회_중_삭제하지_않는다(String json, CapturedOutput output) {
        given(values.get(KEY)).willReturn(json);

        ProfileCacheLookupResult result = cache.get(USER_ID);

        assertThat(result.status()).isEqualTo(ProfileCacheLookupResult.Status.INVALID);
        assertThat(result.profile()).isNull();
        verify(redisTemplate, never()).delete(anyString());
        assertOutcome("get", "invalid");
        assertThat(output).containsAnyOf(
            "errorCode=" + ProfileCacheErrorCode.PROFILE_CACHE_DESERIALIZATION_FAILED.name(),
            "errorCode=" + ProfileCacheErrorCode.PROFILE_CACHE_DATA_INVALID.name());
    }

    @Test
    void Redis_조회_실패를_미적중과_구분하고_예외_내용을_로그에_노출하지_않는다(CapturedOutput output) {
        given(values.get(KEY)).willThrow(new RedisConnectionFailureException("private-signed-url-token"));

        ProfileCacheLookupResult result = cache.get(USER_ID);

        assertThat(result.status()).isEqualTo(ProfileCacheLookupResult.Status.READ_ERROR);
        assertThat(result.profile()).isNull();
        assertOutcome("get", "read_error");
        assertThat(output).contains("errorCode=" + ProfileCacheErrorCode.PROFILE_CACHE_READ_FAILED.name(),
            "operation=get", "exceptionType=RedisConnectionFailureException")
            .doesNotContain("private-signed-url-token");
    }

    @Test
    void 응답을_JSON으로_변환하고_남은_TTL과_함께_저장한다() {
        UserProfileResponse profile = profile();

        cache.put(USER_ID, profile, NOW.plusSeconds(180));

        verify(values).set(KEY, jsonMapper.writeValueAsString(profile), Duration.ofSeconds(180));
        assertOutcome("put", "stored");
    }

    @Test
    void JSON_변환에_사용한_시간을_제외한_TTL로_저장한다() {
        UserProfileResponse profile = profile();
        JsonMapper delayedMapper = mock(JsonMapper.class);
        given(delayedMapper.writeValueAsString(profile)).willAnswer(invocation -> {
            given(clock.instant()).willReturn(NOW.plusSeconds(2));
            return jsonMapper.writeValueAsString(profile);
        });
        cache = new UserProfileCache(redisTemplate, delayedMapper, meterRegistry, clock);

        cache.put(USER_ID, profile, NOW.plusSeconds(180));

        verify(values).set(KEY, jsonMapper.writeValueAsString(profile), Duration.ofSeconds(178));
        assertOutcome("put", "stored");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1_000_000L, 0L, 999_999L})
    void 저장_가능한_시간이_1밀리초_미만이면_JSON_변환과_쓰기를_생략한다(long nanos) {
        JsonMapper unusedMapper = mock(JsonMapper.class);
        cache = new UserProfileCache(redisTemplate, unusedMapper, meterRegistry, clock);

        cache.put(USER_ID, profile(), NOW.plusNanos(nanos));

        verifyNoInteractions(unusedMapper, values);
        assertOutcome("put", "expired");
    }

    @Test
    void JSON_변환_도중_기한이_지나면_Redis에_저장하지_않는다() {
        UserProfileResponse profile = profile();
        JsonMapper delayedMapper = mock(JsonMapper.class);
        given(delayedMapper.writeValueAsString(profile)).willAnswer(invocation -> {
            given(clock.instant()).willReturn(NOW.plusSeconds(180));
            return jsonMapper.writeValueAsString(profile);
        });
        cache = new UserProfileCache(redisTemplate, delayedMapper, meterRegistry, clock);

        cache.put(USER_ID, profile, NOW.plusSeconds(180));

        verifyNoInteractions(values);
        assertOutcome("put", "expired");
    }

    @Test
    void JSON_변환_실패를_기록하고_Redis_쓰기를_생략한다(CapturedOutput output) {
        UserProfileResponse profile = profile();
        JsonMapper failedMapper = mock(JsonMapper.class);
        given(failedMapper.writeValueAsString(profile)).willThrow(mock(JacksonException.class));
        cache = new UserProfileCache(redisTemplate, failedMapper, meterRegistry, clock);

        assertThatCode(() -> cache.put(USER_ID, profile, NOW.plusSeconds(180))).doesNotThrowAnyException();

        verifyNoInteractions(values);
        assertOutcome("put", "serialization_error");
        assertThat(output).contains("errorCode=" + ProfileCacheErrorCode.PROFILE_CACHE_SERIALIZATION_FAILED.name());
    }

    @Test
    void Redis_쓰기_실패는_기록하고_호출자에게_전파하지_않는다(CapturedOutput output) {
        doThrow(new RedisConnectionFailureException(ProfileCacheErrorCode.PROFILE_CACHE_WRITE_FAILED.name()))
            .when(values).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> cache.put(USER_ID, profile(), NOW.plusSeconds(180))).doesNotThrowAnyException();

        assertOutcome("put", "write_error");
        assertThat(output).contains("errorCode=" + ProfileCacheErrorCode.PROFILE_CACHE_WRITE_FAILED.name());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 삭제한_키와_이미_없는_키를_정상_처리한다(boolean deleted) {
        given(redisTemplate.delete(KEY)).willReturn(deleted);

        cache.evict(USER_ID);

        verify(redisTemplate).delete(KEY);
        assertOutcome("evict", deleted ? "deleted" : "absent");
    }

    @Test
    void Redis_삭제_실패는_기록하고_호출자에게_전파하지_않는다(CapturedOutput output) {
        given(redisTemplate.delete(KEY))
            .willThrow(new RedisConnectionFailureException(ProfileCacheErrorCode.PROFILE_CACHE_DELETE_FAILED.name()));

        assertThatCode(() -> cache.evict(USER_ID)).doesNotThrowAnyException();

        assertOutcome("evict", "delete_error");
        assertThat(output).contains("errorCode=" + ProfileCacheErrorCode.PROFILE_CACHE_DELETE_FAILED.name());
    }

    @Test
    void 예상한_Redis_오류가_아닌_프로그래밍_오류는_숨기지_않는다() {
        IllegalStateException failure = new IllegalStateException(CommonErrorCode.INTERNAL_SERVER_ERROR.name());
        given(values.get(KEY)).willThrow(failure);

        assertThatThrownBy(() -> cache.get(USER_ID)).isSameAs(failure);
        assertThat(meterRegistry.getMeters()).isEmpty();
    }

    @Test
    void 다른_회원의_프로필을_요청한_키에_저장하지_않는다() {
        assertErrorCode(() -> cache.put(22L, profile(), NOW.plusSeconds(180)),
            ProfileCacheErrorCode.PROFILE_CACHE_PROFILE_INVALID);
        verifyNoInteractions(values);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L})
    void 잘못된_회원_ID로_Redis에_접근하지_않는다(Long userId) {
        assertErrorCode(() -> cache.get(userId), ProfileCacheErrorCode.PROFILE_CACHE_USER_ID_INVALID);
        assertErrorCode(() -> cache.put(userId, profile(), NOW.plusSeconds(180)),
            ProfileCacheErrorCode.PROFILE_CACHE_USER_ID_INVALID);
        assertErrorCode(() -> cache.evict(userId), ProfileCacheErrorCode.PROFILE_CACHE_USER_ID_INVALID);
        verifyNoInteractions(values);
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void 저장_만료시각이_없으면_오류코드로_거절한다() {
        assertErrorCode(() -> cache.put(USER_ID, profile(), null),
            ProfileCacheErrorCode.PROFILE_CACHE_EXPIRATION_REQUIRED);
        verifyNoInteractions(values);
    }

    private void assertErrorCode(Runnable operation, ProfileCacheErrorCode errorCode) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ProfileCacheBusinessException.class,
            failure -> assertThat(failure.getErrorCode()).isEqualTo(errorCode));
    }

    private void assertOutcome(String operation, String result) {
        String name = "profile.cache." + operation + ".requests";
        assertThat(meterRegistry.get(name).tag("result", result).counter().count()).isEqualTo(1);
        assertThat(meterRegistry.find(name).counters().stream().mapToDouble(Counter::count).sum()).isEqualTo(1);
        assertThat(meterRegistry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
            .allSatisfy(tag -> assertThat(tag.getKey()).isEqualTo("result")));
    }

    private UserProfileResponse profile() {
        return new UserProfileResponse(USER_ID, "회원", 29, JobCategory.IT_DATA, "서울특별시 강남구", (short) 175,
            BodyType.AVERAGE, EducationLevel.BACHELOR, Religion.NONE, Drinking.SOCIAL, Smoking.NON_SMOKER,
            Mbti.INTJ, List.of(new UserProfileResponse.Image(701L, (short) 1,
                "https://example.com/701?signature=private-token")));
    }
}
