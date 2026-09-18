package com.team.dating_backend.user.dto.request;

import com.team.dating_backend.user.enums.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record OnboardingIdentityRequest(
        @NotBlank String name, @NotNull LocalDate birthDate, @NotNull Gender gender) {}
