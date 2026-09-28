package com.team.dating_backend.onboarding.dto.response;

import com.team.dating_backend.user.enums.Gender;
import java.time.LocalDate;

public record OnboardingProfileResponse(Long userId, LocalDate birthDate, Gender gender) {}
