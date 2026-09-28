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

    private static final Long USER_ID = 1L;
    private static final String NICKNAME = "하리";

    private ProfileRepository profileRepository;
    private NicknameAvailabilityService nicknameAvailabilityService;

    @BeforeEach
    void setUp() {
        profileRepository = mock(ProfileRepository.class);
        nicknameAvailabilityService = new NicknameAvailabilityService(profileRepository);
    }

    @Test
    void 사용하지_않는_닉네임이면_사용_가능하다() {
        given(profileRepository.existsByNicknameAndDeletedAtIsNullAndUserIdNot(NICKNAME, USER_ID))
            .willReturn(false);

        NicknameAvailabilityResponse response = nicknameAvailabilityService
            .checkNicknameAvailability(NICKNAME, USER_ID);

        assertThat(response.available()).isTrue();
        verify(profileRepository)
            .existsByNicknameAndDeletedAtIsNullAndUserIdNot(NICKNAME, USER_ID);
    }

    @Test
    void 사용자의_기존_닉네임은_중복으로_판단하지_않는다() {
        given(profileRepository.existsByNicknameAndDeletedAtIsNullAndUserIdNot(NICKNAME, USER_ID))
            .willReturn(false);

        NicknameAvailabilityResponse response = nicknameAvailabilityService
            .checkNicknameAvailability(NICKNAME, USER_ID);

        assertThat(response.available()).isTrue();
        verify(profileRepository)
            .existsByNicknameAndDeletedAtIsNullAndUserIdNot(NICKNAME, USER_ID);
    }

    @Test
    void 다른_사용자가_동일한_닉네임을_사용하면_사용_불가하다() {
        given(profileRepository.existsByNicknameAndDeletedAtIsNullAndUserIdNot(NICKNAME, USER_ID))
            .willReturn(true);

        NicknameAvailabilityResponse response = nicknameAvailabilityService
            .checkNicknameAvailability(NICKNAME, USER_ID);

        assertThat(response.available()).isFalse();
        verify(profileRepository)
            .existsByNicknameAndDeletedAtIsNullAndUserIdNot(NICKNAME, USER_ID);
    }
}
