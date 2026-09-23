package com.team.dating_backend.onboarding.dto.response;

import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.enums.BodyType;
import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.EducationLevel;
import com.team.dating_backend.profile.enums.Mbti;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;

public record ProfileSaveProfileResponse(
        Long activityRegionId,
        String nickname,
        Short height,
        BodyType bodyType,
        EducationLevel educationLevel,
        String job,
        Religion religion,
        Mbti mbti,
        Drinking drinking,
        Smoking smoking) {

    public static ProfileSaveProfileResponse from(Profile profile) {
        Long activityRegionId =
                profile.getActivityRegion() == null ? null : profile.getActivityRegion().getId();

        return new ProfileSaveProfileResponse(
                activityRegionId,
                profile.getNickname(),
                profile.getHeight(),
                profile.getBodyType(),
                profile.getEducationLevel(),
                profile.getJob(),
                profile.getReligion(),
                profile.getMbti(),
                profile.getDrinking(),
                profile.getSmoking());
    }
}
