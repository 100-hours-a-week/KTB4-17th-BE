package com.team.dating_backend.user.dto.request;

import com.team.dating_backend.user.enums.Gender;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record UserRegistrationRequest(
    @NotNull LocalDate birthDate,
    @NotNull Gender gender) {}
