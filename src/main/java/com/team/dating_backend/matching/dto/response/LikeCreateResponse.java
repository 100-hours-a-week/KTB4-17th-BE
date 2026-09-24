package com.team.dating_backend.matching.dto.response;

import com.team.dating_backend.matching.enums.LikeStatus;

public record LikeCreateResponse(Long likeId, LikeStatus status) {}
