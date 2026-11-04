package com.team.dating_backend.profile.service;

import com.team.dating_backend.profile.cache.ProfileCacheExpirationPolicy;
import com.team.dating_backend.profile.cache.ProfileCacheLookupResult;
import com.team.dating_backend.profile.cache.UserProfileCache;
import com.team.dating_backend.profile.config.ProfileCacheProperties;
import com.team.dating_backend.profile.dto.UserProfileLoadResult;
import com.team.dating_backend.profile.dto.response.UserProfileResponse;
import com.team.dating_backend.profile.enums.ProfileImageErrorCode;
import com.team.dating_backend.profile.exception.ProfileImageBusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserProfileGetService {

    private final ProfileAvailabilityValidator profileAvailabilityValidator;
    private final UserProfileSourceLoader sourceLoader;
    private final UserProfileCache cache;
    private final ProfileCacheExpirationPolicy expirationPolicy;
    private final ProfileCacheProperties cacheProperties;

    public UserProfileResponse getUserProfile(Long viewerUserId, Long userId) {
        if (viewerUserId == null || userId == null || userId <= 0) {
            throw new ProfileImageBusinessException(ProfileImageErrorCode.PROFILE_NOT_FOUND);
        }
        profileAvailabilityValidator.validate(userId);
        if (!cacheProperties.isEnabled()) {
            return sourceLoader.load(userId).profile();
        }
        ProfileCacheLookupResult lookup = cache.get(userId);
        return switch (lookup.status()) {
            case HIT -> lookup.profile();
            case MISS, INVALID -> loadAndCache(userId);
            case READ_ERROR -> sourceLoader.load(userId).profile();
        };
    }

    private UserProfileResponse loadAndCache(Long userId) {
        UserProfileLoadResult loaded = sourceLoader.load(userId);
        expirationPolicy.expiresAt(loaded).ifPresent(deadline -> cache.put(userId, loaded.profile(), deadline));
        return loaded.profile();
    }
}
