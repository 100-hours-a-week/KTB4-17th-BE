package com.team.dating_backend.chat.entity;

import com.team.dating_backend.chat.enums.ChatParticipantStatus;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "chat_participants", uniqueConstraints = @UniqueConstraint(name = "uk_chat_participant_room_user", columnNames = {
    "chat_room_id",
    "user_id"}), indexes = @Index(name = "idx_chat_participant_user_status_room", columnList = "user_id,status,chat_room_id"))
public class ChatParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_room_id", nullable = false)
    private Long chatRoomId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ChatParticipantStatus status;

    @Column(name = "is_chat_notification", nullable = false)
    private boolean chatNotification;

    @Column(name = "left_at")
    private LocalDateTime leftAt;

    @Column(name = "last_read_message_id")
    private Long lastReadMessageId;

    public ChatParticipant(Long chatRoomId, Long userId) {
        this.chatRoomId = chatRoomId;
        this.userId = userId;
        this.status = ChatParticipantStatus.ACTIVE;
        this.chatNotification = true;
    }
}
