package com.team.dating_backend.profile.cache;

import com.team.dating_backend.profile.dto.response.UserProfileResponse;
import com.team.dating_backend.profile.enums.ProfileCacheErrorCode;
import com.team.dating_backend.profile.exception.ProfileCacheBusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
public class UserProfileCache {

    private static final String KEY_PREFIX = "profile:detail:";

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Autowired
    public UserProfileCache(
        @Qualifier("profileCacheStringRedisTemplate") StringRedisTemplate redisTemplate,
        JsonMapper jsonMapper, MeterRegistry meterRegistry) {
        this(redisTemplate, jsonMapper, meterRegistry, Clock.systemUTC());
    }

    UserProfileCache(StringRedisTemplate redisTemplate, JsonMapper jsonMapper,
        MeterRegistry meterRegistry, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.jsonMapper = jsonMapper;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public ProfileCacheLookupResult get(Long userId) {
        String key = key(userId);
        String json;
        try {
            json = redisTemplate.opsForValue().get(key);
        } catch (DataAccessException failure) {
            logFailure("get", userId, ProfileCacheErrorCode.PROFILE_CACHE_READ_FAILED, failure);
            return lookup(ProfileCacheLookupResult.Status.READ_ERROR, null);
        }
        if (json == null) {
            return lookup(ProfileCacheLookupResult.Status.MISS, null);
        }

        UserProfileResponse profile;
        try {
            profile = jsonMapper.readValue(json, UserProfileResponse.class);
        } catch (JacksonException failure) {
            logFailure("get", userId, ProfileCacheErrorCode.PROFILE_CACHE_DESERIALIZATION_FAILED, failure);
            return lookup(ProfileCacheLookupResult.Status.INVALID, null);
        }
        if (!isUsable(userId, profile)) {
            log.warn("errorCode={}, operation=get, userId={}",
                ProfileCacheErrorCode.PROFILE_CACHE_DATA_INVALID, userId);
            return lookup(ProfileCacheLookupResult.Status.INVALID, null);
        }
        return lookup(ProfileCacheLookupResult.Status.HIT, profile);
    }

    public void put(Long userId, UserProfileResponse profile, Instant cacheExpiresAt) {
        String key = key(userId);
        if (cacheExpiresAt == null) {
            throw new ProfileCacheBusinessException(ProfileCacheErrorCode.PROFILE_CACHE_EXPIRATION_REQUIRED);
        }
        if (!isUsable(userId, profile)) {
            throw new ProfileCacheBusinessException(ProfileCacheErrorCode.PROFILE_CACHE_PROFILE_INVALID);
        }
        if (remainingMillis(cacheExpiresAt) <= 0) {
            record("put", "expired");
            return;
        }

        String json;
        try {
            json = jsonMapper.writeValueAsString(profile);
        } catch (JacksonException failure) {
            logFailure("put", userId, ProfileCacheErrorCode.PROFILE_CACHE_SERIALIZATION_FAILED, failure);
            record("put", "serialization_error");
            return;
        }
        long ttlMillis = remainingMillis(cacheExpiresAt);
        if (ttlMillis <= 0) {
            record("put", "expired");
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, json, Duration.ofMillis(ttlMillis));
        } catch (DataAccessException failure) {
            logFailure("put", userId, ProfileCacheErrorCode.PROFILE_CACHE_WRITE_FAILED, failure);
            record("put", "write_error");
            return;
        }
        record("put", "stored");
    }

    public void evict(Long userId) {
        String key = key(userId);
        try {
            boolean deleted = Boolean.TRUE.equals(redisTemplate.delete(key));
            record("evict", deleted ? "deleted" : "absent");
        } catch (DataAccessException failure) {
            logFailure("evict", userId, ProfileCacheErrorCode.PROFILE_CACHE_DELETE_FAILED, failure);
            record("evict", "delete_error");
        }
    }

    private static String key(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ProfileCacheBusinessException(ProfileCacheErrorCode.PROFILE_CACHE_USER_ID_INVALID);
        }
        return KEY_PREFIX + userId;
    }

    private static boolean isUsable(Long userId, UserProfileResponse profile) {
        return profile != null && userId.equals(profile.userId()) && profile.images() != null
            && profile.images().stream().allMatch(Objects::nonNull);
    }

    private long remainingMillis(Instant cacheExpiresAt) {
        return Duration.between(clock.instant(), cacheExpiresAt).toMillis();
    }

    private ProfileCacheLookupResult lookup(ProfileCacheLookupResult.Status status, UserProfileResponse profile) {
        record("get", status.name().toLowerCase(Locale.ROOT));
        return new ProfileCacheLookupResult(status, profile);
    }

    private void record(String operation, String result) {
        meterRegistry.counter("profile.cache." + operation + ".requests", "result", result).increment();
    }

    private void logFailure(String operation, Long userId, ProfileCacheErrorCode errorCode, RuntimeException failure) {
        log.warn("errorCode={}, operation={}, userId={}, exceptionType={}",
            errorCode, operation, userId, failure.getClass().getSimpleName());
    }
}
