package com.team.dating_backend.chat.entity;

import com.team.dating_backend.chat.enums.ChatMessageStatus;
import com.team.dating_backend.chat.enums.ChatMessageType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "chat_messages", uniqueConstraints = @UniqueConstraint(name = "uk_chat_message_sender_client_id", columnNames = {
    "sender_id",
    "client_message_id"}), indexes = @Index(name = "idx_chat_message_room_status_id", columnList = "chat_room_id,status,id"))
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_room_id", nullable = false)
    private Long chatRoomId;

    @Column(name = "sender_id", nullable = false)
    private Long senderParticipantId;

    @Column(name = "client_message_id", nullable = false, updatable = false)
    private UUID clientMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 30)
    private ChatMessageType messageType;

    @Column(name = "text_content", length = 1000)
    private String textContent;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ChatMessageStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "sender_deleted_at")
    private LocalDateTime senderDeletedAt;

    @Column(name = "receiver_deleted_at")
    private LocalDateTime receiverDeletedAt;

    public ChatMessage(
        Long chatRoomId,
        Long senderParticipantId,
        UUID clientMessageId,
        String textContent,
        LocalDateTime createdAt) {
        this.chatRoomId = chatRoomId;
        this.senderParticipantId = senderParticipantId;
        this.clientMessageId = clientMessageId;
        this.messageType = ChatMessageType.TEXT;
        this.textContent = textContent;
        this.status = ChatMessageStatus.SENT;
        this.createdAt = createdAt;
    }
}
