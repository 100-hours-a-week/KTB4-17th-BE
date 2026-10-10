package com.team.dating_backend.user.dto.response;

import java.time.LocalDateTime;

public record UserBlockResponse(Long targetUserId, LocalDateTime blockedAt) {}
