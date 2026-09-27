package com.team.dating_backend.chat.dto.request;

import com.team.dating_backend.chat.enums.ChatMessageType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ChatMessageCreateRequest(
    @NotNull UUID clientMessageId,
    @NotNull ChatMessageType messageType,
    @Size(max = 1000) String textContent,
    Long imageFileId) {}
