package com.team.dating_backend.profile.dto.response;

import com.team.dating_backend.profile.enums.BodyType;
import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.EducationLevel;
import com.team.dating_backend.profile.enums.JobCategory;
import com.team.dating_backend.profile.enums.Mbti;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import java.util.List;

public record UserProfileResponse(
    Long userId,
    String nickname,
    Integer age,
    JobCategory job,
    String region,
    Short height,
    BodyType bodyType,
    EducationLevel educationLevel,
    Religion religion,
    Drinking drinking,
    Smoking smoking,
    Mbti mbti,
    List<Image> images) {

    public record Image(Long fileId, short displayOrder, String imageUrl) {}
}
