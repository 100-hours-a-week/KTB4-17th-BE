package com.team.dating_backend.matching.dto.response;

import com.team.dating_backend.matching.enums.LikeStatus;
import java.time.LocalDateTime;

public record ReceivedLikeItemResponse(
    Long likeId,
    ReceivedLikeSenderResponse sender,
    LikeStatus status,
    LocalDateTime createdAt) {}
