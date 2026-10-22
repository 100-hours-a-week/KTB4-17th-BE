package com.team.dating_backend.chat.delegation.service;

import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.RecentMessage;
import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.Speaker;
import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.enums.ChatMessageStatus;
import com.team.dating_backend.chat.enums.ChatMessageType;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatDelegationConversationService {

    private static final int RECENT_MESSAGE_LIMIT = 20;
    private static final String IMAGE_MESSAGE = "(사진이 전달되었습니다)";

    private final ChatMessageRepository messageRepository;

    public MessageContext latest(Long chatRoomId, Long requesterParticipantId) {
        return context(messageRepository.findRecentByChatRoomIdAndStatus(
            chatRoomId, ChatMessageStatus.SENT, Limit.of(RECENT_MESSAGE_LIMIT)),
            requesterParticipantId);
    }

    public MessageContext upTo(
        Long chatRoomId, Long requesterParticipantId, Long contextMessageId) {
        return context(messageRepository.findRecentByChatRoomIdAndStatusUpTo(
            chatRoomId, ChatMessageStatus.SENT, contextMessageId,
            Limit.of(RECENT_MESSAGE_LIMIT)), requesterParticipantId);
    }

    private MessageContext context(
        List<ChatMessage> recentMessages, Long requesterParticipantId) {
        if (recentMessages.isEmpty()) {
            throw new ChatBusinessException(
                ChatErrorCode.CHAT_DELEGATION_CONVERSATION_REQUIRED);
        }
        List<ChatMessage> messages = new ArrayList<>(recentMessages);
        Long messageId = messages.getFirst().getId();
        Collections.reverse(messages);
        return new MessageContext(messageId, messages.stream()
            .map(message -> toRecentMessage(message, requesterParticipantId))
            .toList());
    }

    private RecentMessage toRecentMessage(
        ChatMessage message, Long requesterParticipantId) {
        Speaker speaker = requesterParticipantId.equals(message.getSenderParticipantId())
            ? Speaker.REQUESTER
            : Speaker.TARGET;
        String content = message.getMessageType() == ChatMessageType.IMAGE
            ? IMAGE_MESSAGE
            : message.getTextContent();
        return new RecentMessage(speaker, content);
    }

    public record MessageContext(Long messageId, List<RecentMessage> messages) {}
}
