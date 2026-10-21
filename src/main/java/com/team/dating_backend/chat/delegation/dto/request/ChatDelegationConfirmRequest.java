package com.team.dating_backend.chat.delegation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ChatDelegationConfirmRequest(
    @NotBlank @Pattern(regexp = "[0-9a-f]{32}") String draftId) {}
