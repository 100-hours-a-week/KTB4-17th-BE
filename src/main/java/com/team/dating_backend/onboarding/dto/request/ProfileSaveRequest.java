package com.team.dating_backend.onboarding.dto.request;

import com.team.dating_backend.profile.enums.BodyType;
import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.EducationLevel;
import com.team.dating_backend.profile.enums.Mbti;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record ProfileSaveRequest(
    @Pattern(regexp = "^[가-힣A-Za-z0-9]{2,10}$", message = "must be 2 to 10 characters using Korean, English letters, or digits") String nickname,
    @Positive(message = "must be greater than 0") Long activityRegionId,
    @Min(value = 130, message = "must be between 130 and 220") @Max(value = 220, message = "must be between 130 and 220") Integer height,
    BodyType bodyType,
    EducationLevel educationLevel,
    @Pattern(regexp = "^(?=.{1,50}$).*\\S.*$", message = "must be non-blank and at most 50 characters") String job,
    Religion religion,
    Drinking drinking,
    Smoking smoking,
    Mbti mbti) {

    public Short heightAsShort() {
        return height == null ? null : height.shortValue();
    }
}
