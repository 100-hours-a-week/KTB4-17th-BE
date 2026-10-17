package com.team.dating_backend.chat.dto.request;

import com.team.dating_backend.chat.enums.ChatRoomPresenceAction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ChatRoomPresenceRequest(
    @NotNull @Positive Long chatRoomId,
    @NotNull ChatRoomPresenceAction action) {}
