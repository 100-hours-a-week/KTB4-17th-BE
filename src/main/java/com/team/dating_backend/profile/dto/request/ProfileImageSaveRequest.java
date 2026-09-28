package com.team.dating_backend.profile.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ProfileImageSaveRequest(
    @NotNull @Size(
        min = 1,
        max = 6
    ) List<@NotNull @Valid ProfileImageSaveItemRequest> images) {}
