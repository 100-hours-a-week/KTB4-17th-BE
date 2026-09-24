package com.team.dating_backend.onboarding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.onboarding.dto.response.NicknameAvailabilityResponse;
import com.team.dating_backend.profile.repository.ProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NicknameAvailabilityServiceTest {

    private static final String NICKNAME = "하리";

    private ProfileRepository profileRepository;
    private NicknameAvailabilityService nicknameAvailabilityService;

    @BeforeEach
    void setUp() {
        profileRepository = mock(ProfileRepository.class);
        nicknameAvailabilityService = new NicknameAvailabilityService(profileRepository);
    }

    @Test
    void 활성_프로필에_동일한_닉네임이_없으면_사용_가능하다() {
        given(profileRepository.existsByNicknameAndDeletedAtIsNull(NICKNAME)).willReturn(false);

        NicknameAvailabilityResponse response = nicknameAvailabilityService.checkNicknameAvailability(NICKNAME);

        assertThat(response.available()).isTrue();
        verify(profileRepository).existsByNicknameAndDeletedAtIsNull(NICKNAME);
    }

    @Test
    void 활성_프로필에_동일한_닉네임이_있으면_사용_불가능하다() {
        given(profileRepository.existsByNicknameAndDeletedAtIsNull(NICKNAME)).willReturn(true);

        NicknameAvailabilityResponse response = nicknameAvailabilityService.checkNicknameAvailability(NICKNAME);

        assertThat(response.available()).isFalse();
        verify(profileRepository).existsByNicknameAndDeletedAtIsNull(NICKNAME);
    }
}
