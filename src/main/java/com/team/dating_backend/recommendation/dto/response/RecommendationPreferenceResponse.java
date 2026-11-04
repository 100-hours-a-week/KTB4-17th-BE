package com.team.dating_backend.recommendation.dto.response;

import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import com.team.dating_backend.recommendation.entity.RecommendationPreference;
import com.team.dating_backend.user.enums.Gender;
import java.util.List;

public record RecommendationPreferenceResponse(
    Short minAge,
    Short maxAge,
    Short minHeight,
    Short maxHeight,
    List<Religion> religion,
    List<Drinking> drinking,
    List<Smoking> smoking,
    List<Gender> preferredGenders) {

    public RecommendationPreferenceResponse(
        Short minAge,
        Short maxAge,
        Short minHeight,
        Short maxHeight,
        List<Religion> religion,
        List<Drinking> drinking,
        List<Smoking> smoking) {
        this(minAge, maxAge, minHeight, maxHeight, religion, drinking, smoking, List.of());
    }

    public static RecommendationPreferenceResponse from(RecommendationPreference preference) {
        return new RecommendationPreferenceResponse(
            preference.getMinAge(),
            preference.getMaxAge(),
            preference.getMinHeight(),
            preference.getMaxHeight(),
            preference.getReligion(),
            preference.getDrinking(),
            preference.getSmoking(),
            preference.getPreferredGenders());
    }

    public static RecommendationPreferenceResponse unrestricted(Gender requesterGender) {
        return new RecommendationPreferenceResponse(
            null, null, null, null, List.of(), List.of(), List.of(),
            RecommendationPreference.defaultTargetGenders(requesterGender));
    }

    public static RecommendationPreferenceResponse unrestricted() {
        return new RecommendationPreferenceResponse(null, null, null, null, List.of(), List.of(), List.of());
    }
}
