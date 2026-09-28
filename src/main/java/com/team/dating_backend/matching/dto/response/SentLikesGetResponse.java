package com.team.dating_backend.matching.dto.response;

import java.util.List;

public record SentLikesGetResponse(
    List<SentLikeItemResponse> items,
    SentLikePageInfo pageInfo) {}
