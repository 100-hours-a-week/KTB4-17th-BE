package com.team.dating_backend.onboarding.service;

import com.team.dating_backend.onboarding.dto.response.NicknameAvailabilityResponse;
import com.team.dating_backend.profile.repository.ProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NicknameAvailabilityService {

    private final ProfileRepository profileRepository;

    @Transactional(readOnly = true)
    public NicknameAvailabilityResponse checkNicknameAvailability(String nickname) {
        boolean exists = profileRepository.existsByNicknameAndDeletedAtIsNull(nickname);
        return new NicknameAvailabilityResponse(!exists);
    }
}
