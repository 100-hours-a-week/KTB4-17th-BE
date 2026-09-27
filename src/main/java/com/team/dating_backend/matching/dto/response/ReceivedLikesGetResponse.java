package com.team.dating_backend.matching.dto.response;

import java.util.List;

public record ReceivedLikesGetResponse(
    List<ReceivedLikeItemResponse> items,
    ReceivedLikePageInfo pageInfo) {}
