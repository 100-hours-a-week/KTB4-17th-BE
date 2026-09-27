package com.team.dating_backend.user.dto.request;

import com.team.dating_backend.user.enums.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

public record UserRegistrationRequest(
    @NotBlank @Pattern(regexp = "^[가-힣]{2,8}$") String name,
    @NotNull LocalDate birthDate,
    @NotNull Gender gender) {}
