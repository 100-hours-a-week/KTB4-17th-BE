package com.team.dating_backend.profile.cache;

import com.team.dating_backend.profile.config.ProfileCacheProperties;
import com.team.dating_backend.profile.dto.UserProfileLoadResult;
import com.team.dating_backend.profile.enums.ProfileCacheErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileCacheExpirationPolicy {

    private final ProfileCacheProperties properties;
    private final MeterRegistry meterRegistry;

    public Optional<Instant> expiresAt(UserProfileLoadResult loaded) {
        Instant deadline = loaded.loadedAt().plus(properties.getMaxTtl());
        if (loaded.profile().images().isEmpty()) {
            return Optional.of(deadline);
        }
        if (loaded.earliestImageExpiresAt() == null) {
            ProfileCacheErrorCode errorCode = ProfileCacheErrorCode.PROFILE_CACHE_IMAGE_EXPIRATION_MISSING;
            log.warn("errorCode={}, userId={}", errorCode, loaded.profile().userId());
            meterRegistry.counter("profile.cache.fill.skipped", "error_code", errorCode.name()).increment();
            return Optional.empty();
        }
        Instant imageDeadline = loaded.earliestImageExpiresAt().minus(properties.getImageRequestBuffer());
        return Optional.of(imageDeadline.isBefore(deadline) ? imageDeadline : deadline);
    }
}
