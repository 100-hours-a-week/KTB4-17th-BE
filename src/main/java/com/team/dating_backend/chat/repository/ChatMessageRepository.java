package com.team.dating_backend.chat.repository;

import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.enums.ChatMessageStatus;
import com.team.dating_backend.chat.enums.ChatMessageType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    Optional<ChatMessage> findBySenderParticipantIdAndClientMessageId(
        Long senderParticipantId, UUID clientMessageId);

    List<ChatMessage> findByChatRoomIdAndStatusAndMessageTypeOrderByIdDesc(
        Long chatRoomId, ChatMessageStatus status, ChatMessageType messageType, Pageable pageable);

    List<ChatMessage> findByChatRoomIdAndStatusAndMessageTypeAndIdLessThanOrderByIdDesc(
        Long chatRoomId,
        ChatMessageStatus status,
        ChatMessageType messageType,
        Long cursorMessageId,
        Pageable pageable);
}
