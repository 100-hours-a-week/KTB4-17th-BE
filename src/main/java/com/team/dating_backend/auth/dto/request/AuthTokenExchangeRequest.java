package com.team.dating_backend.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthTokenExchangeRequest(
    @NotBlank @Size(max = 64) String code) {}
