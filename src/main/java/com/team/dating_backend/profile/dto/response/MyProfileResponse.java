package com.team.dating_backend.profile.dto.response;

import com.team.dating_backend.profile.enums.BodyType;
import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.EducationLevel;
import com.team.dating_backend.profile.enums.Mbti;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import java.time.LocalDate;
import java.util.List;

public record MyProfileResponse(
    String nickname,
    LocalDate birthDate,
    Long activityRegionId,
    String activityRegionName,
    Short height,
    BodyType bodyType,
    EducationLevel educationLevel,
    String job,
    Religion religion,
    Mbti mbti,
    Drinking drinking,
    Smoking smoking,
    String profileImageUrl,
    List<Image> images) {

    public record Image(
        Long fileId,
        short displayOrder,
        boolean isFrontal,
        String imageUrl) {}
}
