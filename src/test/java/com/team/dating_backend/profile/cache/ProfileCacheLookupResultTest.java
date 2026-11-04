package com.team.dating_backend.profile.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.profile.dto.response.UserProfileResponse;
import com.team.dating_backend.profile.enums.ProfileCacheErrorCode;
import com.team.dating_backend.profile.exception.ProfileCacheBusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ProfileCacheLookupResultTest {

    @Test
    void 조회상태가_없으면_오류코드로_거절한다() {
        assertThatThrownBy(() -> new ProfileCacheLookupResult(null, null))
            .isInstanceOfSatisfying(ProfileCacheBusinessException.class,
                failure -> assertThat(failure.getErrorCode())
                    .isEqualTo(ProfileCacheErrorCode.PROFILE_CACHE_LOOKUP_STATUS_REQUIRED));
    }

    @Test
    void 적중인데_프로필이_없으면_오류코드로_거절한다() {
        assertInvalidResult(ProfileCacheLookupResult.Status.HIT, null);
    }

    @ParameterizedTest
    @EnumSource(value = ProfileCacheLookupResult.Status.class, names = "HIT", mode = EnumSource.Mode.EXCLUDE)
    void 적중이_아닌데_프로필이_있으면_오류코드로_거절한다(ProfileCacheLookupResult.Status status) {
        UserProfileResponse profile = new UserProfileResponse(21L, "회원", null, null, null, null,
            null, null, null, null, null, null, List.of());
        assertInvalidResult(status, profile);
    }

    private void assertInvalidResult(ProfileCacheLookupResult.Status status, UserProfileResponse profile) {
        assertThatThrownBy(() -> new ProfileCacheLookupResult(status, profile))
            .isInstanceOfSatisfying(ProfileCacheBusinessException.class,
                failure -> assertThat(failure.getErrorCode())
                    .isEqualTo(ProfileCacheErrorCode.PROFILE_CACHE_LOOKUP_RESULT_INVALID));
    }
}
