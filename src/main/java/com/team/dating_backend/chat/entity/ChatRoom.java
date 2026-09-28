package com.team.dating_backend.chat.entity;

import com.team.dating_backend.chat.enums.ChatRoomEndReason;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import com.team.dating_backend.matching.entity.Match;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "chat_rooms")
public class ChatRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(
        fetch = FetchType.LAZY,
        optional = false
    )
    @JoinColumn(
        name = "match_id",
        nullable = false,
        unique = true,
        foreignKey = @ForeignKey(name = "fk_chat_room_match")
    )
    private Match match;

    @OneToMany(mappedBy = "chatRoom", fetch = FetchType.LAZY)
    @Getter(AccessLevel.NONE)
    private List<ChatParticipant> participants = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ChatRoomStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", length = 30)
    private ChatRoomEndReason endReason;

    public ChatRoom(Match match, LocalDateTime createdAt) {
        this.match = Objects.requireNonNull(match);
        this.status = ChatRoomStatus.ACTIVE;
        this.createdAt = createdAt;
    }

    public ChatParticipant addParticipant(Long userId) {
        ChatParticipant participant = new ChatParticipant(this, userId);
        participants.add(participant);
        return participant;
    }

    public List<ChatParticipant> getParticipants() {
        return Collections.unmodifiableList(participants);
    }

    public void end(ChatRoomEndReason reason, LocalDateTime endedAt) {
        Objects.requireNonNull(reason);
        Objects.requireNonNull(endedAt);
        if (status == ChatRoomStatus.ACTIVE) {
            status = ChatRoomStatus.ENDED;
            endReason = reason;
            this.endedAt = endedAt;
        }
    }
}
