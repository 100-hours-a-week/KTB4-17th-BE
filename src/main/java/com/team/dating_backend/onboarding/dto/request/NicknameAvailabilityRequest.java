package com.team.dating_backend.onboarding.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record NicknameAvailabilityRequest(
    @NotNull(message = "must be 2 to 10 characters using Korean, English letters, or digits") @Pattern(regexp = "^[가-힣A-Za-z0-9]{2,10}$", message = "must be 2 to 10 characters using Korean, English letters, or digits") String nickname) {}
