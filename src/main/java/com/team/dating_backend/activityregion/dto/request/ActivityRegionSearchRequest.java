package com.team.dating_backend.activityregion.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ActivityRegionSearchRequest(@NotBlank String query) {

    public String normalizedQuery() {
        return query.trim();
    }
}
