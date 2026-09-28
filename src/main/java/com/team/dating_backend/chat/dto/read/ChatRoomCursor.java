package com.team.dating_backend.chat.dto.read;

import java.time.LocalDateTime;

public record ChatRoomCursor(LocalDateTime activityAt, Long chatRoomId) {}
