package com.team.dating_backend.recommendation.dto.response;

import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import com.team.dating_backend.recommendation.entity.RecommendationPreference;
import java.util.List;

public record RecommendationPreferenceResponse(
    Short minAge,
    Short maxAge,
    Short minHeight,
    Short maxHeight,
    List<Religion> religion,
    List<Drinking> drinking,
    List<Smoking> smoking) {

    public static RecommendationPreferenceResponse from(RecommendationPreference preference) {
        return new RecommendationPreferenceResponse(
            preference.getMinAge(),
            preference.getMaxAge(),
            preference.getMinHeight(),
            preference.getMaxHeight(),
            preference.getReligion(),
            preference.getDrinking(),
            preference.getSmoking());
    }

    public static RecommendationPreferenceResponse unrestricted() {
        return new RecommendationPreferenceResponse(null, null, null, null, List.of(), List.of(), List.of());
    }
}
