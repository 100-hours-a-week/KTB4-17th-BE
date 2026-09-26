package com.team.dating_backend.chat.dto.read;

import com.team.dating_backend.chat.enums.ChatRoomPreviewType;

public record ChatRoomPreview(ChatRoomPreviewType type, String text) {}
