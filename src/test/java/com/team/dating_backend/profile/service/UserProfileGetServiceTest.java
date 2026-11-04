package com.team.dating_backend.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.profile.cache.ProfileCacheExpirationPolicy;
import com.team.dating_backend.profile.cache.ProfileCacheLookupResult;
import com.team.dating_backend.profile.cache.UserProfileCache;
import com.team.dating_backend.profile.config.ProfileCacheProperties;
import com.team.dating_backend.profile.dto.UserProfileLoadResult;
import com.team.dating_backend.profile.dto.response.UserProfileResponse;
import com.team.dating_backend.profile.enums.ProfileImageErrorCode;
import com.team.dating_backend.profile.exception.ProfileImageBusinessException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class UserProfileGetServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T03:00:00Z");
    private ProfileAvailabilityValidator validator;
    private UserProfileSourceLoader loader;
    private UserProfileCache cache;
    private ProfileCacheExpirationPolicy policy;
    private ProfileCacheProperties properties;
    private UserProfileGetService service;
    private UserProfileResponse profile;
    private UserProfileLoadResult loaded;

    @BeforeEach
    void setUp() {
        validator = mock(ProfileAvailabilityValidator.class);
        loader = mock(UserProfileSourceLoader.class);
        cache = mock(UserProfileCache.class);
        policy = mock(ProfileCacheExpirationPolicy.class);
        properties = new ProfileCacheProperties();
        properties.setEnabled(true);
        service = new UserProfileGetService(validator, loader, cache, policy, properties);
        profile = new UserProfileResponse(21L, "회원", 29, null, "서울특별시 강남구", null,
            null, null, null, null, null, null, List.of());
        loaded = new UserProfileLoadResult(profile, NOW, null);
    }

    @Test
    void 캐시가_적중해도_먼저_상태를_검증하고_본문을_조회하지_않는다() {
        given(cache.get(21L)).willReturn(new ProfileCacheLookupResult(ProfileCacheLookupResult.Status.HIT, profile));

        assertThat(service.getUserProfile(5L, 21L)).isSameAs(profile);

        var order = inOrder(validator, cache);
        order.verify(validator).validate(21L);
        order.verify(cache).get(21L);
        verifyNoInteractions(loader, policy);
        verify(cache, never()).put(any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = ProfileCacheLookupResult.Status.class, names = {"MISS", "INVALID"})
    void 미적중이나_손상이면_DB_응답을_만들고_정해진_기한으로_저장한다(ProfileCacheLookupResult.Status status) {
        given(cache.get(21L)).willReturn(new ProfileCacheLookupResult(status, null));
        given(loader.load(21L)).willReturn(loaded);
        given(policy.expiresAt(loaded)).willReturn(Optional.of(NOW.plusSeconds(180)));

        assertThat(service.getUserProfile(5L, 21L)).isSameAs(profile);

        var order = inOrder(validator, cache, loader, policy);
        order.verify(validator).validate(21L);
        order.verify(cache).get(21L);
        order.verify(loader).load(21L);
        order.verify(policy).expiresAt(loaded);
        order.verify(cache).put(21L, profile, NOW.plusSeconds(180));
    }

    @Test
    void Redis_읽기_실패이면_DB_응답만_반환하고_저장을_생략한다() {
        given(cache.get(21L))
            .willReturn(new ProfileCacheLookupResult(ProfileCacheLookupResult.Status.READ_ERROR, null));
        given(loader.load(21L)).willReturn(loaded);

        assertThat(service.getUserProfile(5L, 21L)).isSameAs(profile);

        verifyNoInteractions(policy);
        verify(cache, never()).put(any(), any(), any());
    }

    @Test
    void 캐시가_비활성화되어도_상태를_검증하고_DB_응답을_반환한다() {
        properties.setEnabled(false);
        given(loader.load(21L)).willReturn(loaded);

        assertThat(service.getUserProfile(5L, 21L)).isSameAs(profile);

        var order = inOrder(validator, loader);
        order.verify(validator).validate(21L);
        order.verify(loader).load(21L);
        verifyNoInteractions(cache, policy);
    }

    @Test
    void 만료_정보가_불충분하면_DB_응답은_반환하고_저장을_생략한다() {
        given(cache.get(21L)).willReturn(new ProfileCacheLookupResult(ProfileCacheLookupResult.Status.MISS, null));
        given(loader.load(21L)).willReturn(loaded);
        given(policy.expiresAt(loaded)).willReturn(Optional.empty());

        assertThat(service.getUserProfile(5L, 21L)).isSameAs(profile);

        verify(cache, never()).put(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(longs = {5L, 21L})
    void 본인이나_상대가_조회_불가이면_캐시와_본문을_조회하지_않는다(Long userId) {
        willThrow(new ProfileImageBusinessException(ProfileImageErrorCode.PROFILE_NOT_FOUND))
            .given(validator).validate(userId);

        assertUnavailable(5L, userId);

        verifyNoInteractions(cache, loader, policy);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L})
    void 잘못된_대상_ID를_먼저_거절한다(Long userId) {
        assertUnavailable(5L, userId);
        verifyNoInteractions(validator, cache, loader, policy);
    }

    @Test
    void 조회자_ID가_없으면_거절한다() {
        assertUnavailable(null, 21L);
        verifyNoInteractions(validator, cache, loader, policy);
    }

    @Test
    void DB_조회가_실패하면_저장하지_않고_기존_오류를_전파한다() {
        given(cache.get(21L)).willReturn(new ProfileCacheLookupResult(ProfileCacheLookupResult.Status.MISS, null));
        given(loader.load(21L)).willThrow(new ProfileImageBusinessException(ProfileImageErrorCode.PROFILE_NOT_FOUND));

        assertUnavailable(5L, 21L);

        verifyNoInteractions(policy);
        verify(cache, never()).put(any(), any(), any());
    }

    private void assertUnavailable(Long viewerUserId, Long userId) {
        assertThatThrownBy(() -> service.getUserProfile(viewerUserId, userId))
            .isInstanceOfSatisfying(ProfileImageBusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ProfileImageErrorCode.PROFILE_NOT_FOUND));
    }
}
