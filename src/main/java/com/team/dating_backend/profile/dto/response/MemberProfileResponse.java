package com.team.dating_backend.profile.dto.response;

import java.util.List;

public record MemberProfileResponse(
    Long memberId,
    String nickname,
    Integer age,
    String job,
    String region,
    List<Image> images) {

    public record Image(Long fileId, short displayOrder, String imageUrl) {}
}
