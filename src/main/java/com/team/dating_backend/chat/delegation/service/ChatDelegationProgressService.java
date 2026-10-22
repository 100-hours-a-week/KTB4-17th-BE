package com.team.dating_backend.chat.delegation.service;

import com.team.dating_backend.chat.delegation.dto.response.ChatDelegationProgressResponse;
import com.team.dating_backend.chat.delegation.entity.ChatDelegation;
import com.team.dating_backend.chat.delegation.entity.ChatDelegationMessage;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationMessageStatus;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationMessageRepository;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationRepository;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatDelegationProgressService {

    private static final int FINAL_TURN = 3;

    private final ChatDelegationRepository delegationRepository;
    private final ChatDelegationMessageRepository messageRepository;

    @Transactional(readOnly = true)
    public ChatDelegationProgressResponse getProgress(
        Long chatRoomId, Long delegationId, Long requesterUserId) {
        ChatDelegation delegation = delegationRepository.findById(delegationId)
            .filter(found -> found.getChatRoom().getId().equals(chatRoomId))
            .orElseThrow(() -> failure(ChatErrorCode.CHAT_DELEGATION_NOT_FOUND));
        if (!delegation.getRequesterParticipant().getUserId().equals(requesterUserId)) {
            throw failure(ChatErrorCode.CHAT_ACCESS_DENIED);
        }

        Map<Integer, ChatDelegationMessage> messages = messageRepository
            .findByDelegationIdOrderByTurn(delegationId).stream()
            .collect(Collectors.toMap(ChatDelegationMessage::getTurn, Function.identity()));
        return new ChatDelegationProgressResponse(
            delegation.getId(),
            delegation.getStatus(),
            IntStream.rangeClosed(1, FINAL_TURN)
                .mapToObj(turn -> toTurnProgress(turn, messages.get(turn)))
                .toList());
    }

    private ChatDelegationProgressResponse.TurnProgress toTurnProgress(
        int turn, ChatDelegationMessage message) {
        boolean completed = message != null
            && message.getStatus() == ChatDelegationMessageStatus.SENT;
        LocalDateTime sentAt = completed ? message.getChatMessage().getCreatedAt() : null;
        return new ChatDelegationProgressResponse.TurnProgress(turn, completed, sentAt);
    }

    private ChatBusinessException failure(ChatErrorCode code) {
        return new ChatBusinessException(code);
    }
}
