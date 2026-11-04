package com.team.dating_backend.profile.dto.response;

import java.time.Instant;
import java.util.List;

public record PopularProfilesResponse(List<Item> profiles) {

    public record Item(int rank, Long userId, String nickname, String region, Long likeCount, Image thumbnail) {}

    public record Image(Long fileId, String imageUrl, Instant expiresAt) {}
}
