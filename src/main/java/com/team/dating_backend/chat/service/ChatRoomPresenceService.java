package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.dto.request.ChatRoomPresenceRequest;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.enums.ChatRoomPresenceAction;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.presence.ChatRoomPresenceRegistry;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ChatRoomPresenceService {

    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;
    private final ChatRoomPresenceRegistry presenceRegistry;

    @Transactional(readOnly = true)
    public void updatePresence(
        Long userId, String sessionId, ChatRoomPresenceRequest request,
        Map<String, Object> sessionAttributes) {
        validateRequest(userId, sessionId, request);
        if (sessionAttributes == null) {
            throw new RequestValidationException();
        }

        if (request.action() != ChatRoomPresenceAction.LEAVE) {
            try {
                requireAccessibleRoom(request.chatRoomId(), userId);
            } catch (ChatBusinessException exception) {
                synchronized (sessionAttributes) {
                    presenceRegistry.leave(userId, sessionId, request.chatRoomId());
                }
                throw exception;
            }
        }

        synchronized (sessionAttributes) {
            if (Boolean.TRUE.equals(sessionAttributes.get(
                ChatRoomPresenceRegistry.SESSION_CLOSED_ATTRIBUTE))) {
                return;
            }
            switch (request.action()) {
                case ENTER -> presenceRegistry.enter(userId, sessionId, request.chatRoomId());
                case HEARTBEAT -> presenceRegistry.heartbeat(userId, sessionId, request.chatRoomId());
                case LEAVE -> presenceRegistry.leave(userId, sessionId, request.chatRoomId());
            }
        }
    }

    private void validateRequest(
        Long userId, String sessionId, ChatRoomPresenceRequest request) {
        if (userId == null || userId <= 0 || !StringUtils.hasText(sessionId)
            || request == null || request.chatRoomId() == null || request.chatRoomId() <= 0
            || request.action() == null) {
            throw new RequestValidationException();
        }
    }

    private void requireAccessibleRoom(Long chatRoomId, Long userId) {
        boolean activeUser = userRepository.findById(userId)
            .map(user -> user.getStatus() == UserStatus.ACTIVE)
            .orElse(false);
        if (!activeUser) {
            throw new ChatBusinessException(ChatErrorCode.CHAT_ACCESS_DENIED);
        }

        ChatRoom room = chatRoomRepository.findById(chatRoomId)
            .orElseThrow(() -> new ChatBusinessException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
        boolean activeParticipant = room.getParticipants().stream()
            .anyMatch(participant -> participant.getUserId().equals(userId)
                && participant.getStatus() == ChatParticipantStatus.ACTIVE);

        if (!activeParticipant || room.getStatus() == ChatRoomStatus.INACTIVE) {
            throw new ChatBusinessException(ChatErrorCode.CHAT_ACCESS_DENIED);
        }
    }
}
