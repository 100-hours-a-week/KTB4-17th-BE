package com.team.dating_backend.profile.cache;

import com.team.dating_backend.profile.dto.response.UserProfileResponse;
import com.team.dating_backend.profile.enums.ProfileCacheErrorCode;
import com.team.dating_backend.profile.exception.ProfileCacheBusinessException;

public record ProfileCacheLookupResult(Status status, UserProfileResponse profile) {

    public ProfileCacheLookupResult {
        if (status == null) {
            throw new ProfileCacheBusinessException(ProfileCacheErrorCode.PROFILE_CACHE_LOOKUP_STATUS_REQUIRED);
        }
        if ((status == Status.HIT) != (profile != null)) {
            throw new ProfileCacheBusinessException(ProfileCacheErrorCode.PROFILE_CACHE_LOOKUP_RESULT_INVALID);
        }
    }

    public enum Status {
        HIT,
        MISS,
        INVALID,
        READ_ERROR
    }
}
