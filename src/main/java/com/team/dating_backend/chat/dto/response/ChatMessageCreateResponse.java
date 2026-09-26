package com.team.dating_backend.chat.dto.response;

import java.time.LocalDateTime;

public record ChatMessageCreateResponse(Long messageId, LocalDateTime createdAt) {}
