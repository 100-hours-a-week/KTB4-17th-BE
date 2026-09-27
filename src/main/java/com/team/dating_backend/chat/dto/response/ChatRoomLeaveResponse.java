package com.team.dating_backend.chat.dto.response;

import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import java.time.LocalDateTime;

public record ChatRoomLeaveResponse(
    Long chatRoomId,
    ChatRoomStatus roomStatus,
    ChatParticipantStatus participantStatus,
    LocalDateTime endedAt) {}
