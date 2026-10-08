package com.team.dating_backend.recommendation.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import java.util.List;

public record RecommendationPreferenceSaveRequest(
    @JsonProperty(required = true) Integer minAge,
    @JsonProperty(required = true) Integer maxAge,
    @JsonProperty(required = true) Integer minHeight,
    @JsonProperty(required = true) Integer maxHeight,
    @JsonProperty(required = true) List<Religion> religion,
    @JsonProperty(required = true) List<Drinking> drinking,
    @JsonProperty(required = true) List<Smoking> smoking) {}
