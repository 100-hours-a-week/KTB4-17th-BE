package com.team.dating_backend.matching.dto.response;

import com.team.dating_backend.matching.enums.LikeStatus;
import java.time.LocalDateTime;

public record SentLikeItemResponse(
    Long likeId,
    SentLikeReceiverResponse receiver,
    LikeStatus status,
    LocalDateTime createdAt) {}
