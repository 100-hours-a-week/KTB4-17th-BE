package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.dto.response.ChatRoomLeaveResponse;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.enums.ChatRoomEndReason;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import com.team.dating_backend.common.exception.RequestValidationException;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatRoomLeaveService {

    private final ChatRoomRepository chatRoomRepository;

    @Transactional
    public ChatRoomLeaveResponse leave(Long chatRoomId, Long userId) {
        if (chatRoomId == null || chatRoomId <= 0 || userId == null || userId <= 0) {
            throw new RequestValidationException();
        }

        ChatRoom room = chatRoomRepository.findWithLockById(chatRoomId)
            .orElseThrow(() -> new ChatBusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        List<ChatParticipant> participants = room.getParticipants();
        ChatParticipant participant = participants.stream()
            .filter(candidate -> candidate.getUserId().equals(userId))
            .findFirst()
            .orElseThrow(() -> new ChatBusinessException(ChatErrorCode.CHAT_ACCESS_DENIED));

        if (room.getStatus() == ChatRoomStatus.INACTIVE) {
            throw new ChatBusinessException(ChatErrorCode.CHAT_ROOM_NOT_ACTIVE);
        }

        if (participant.getStatus() == ChatParticipantStatus.ACTIVE) {
            LocalDateTime now = LocalDateTime.now();
            participant.leave(now);
            room.end(ChatRoomEndReason.USER_LEFT_CHAT, now);
        }

        return new ChatRoomLeaveResponse(
            room.getId(), room.getStatus(), participant.getStatus(), room.getEndedAt());
    }
}
