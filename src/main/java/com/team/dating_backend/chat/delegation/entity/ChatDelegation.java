package com.team.dating_backend.chat.delegation.entity;

import com.team.dating_backend.chat.delegation.enums.ChatDelegationEndType;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "chat_delegations")
public class ChatDelegation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "chat_room_id", nullable = false, unique = true,
        foreignKey = @ForeignKey(name = "fk_chat_delegation_room")
    )
    private ChatRoom chatRoom;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "requester_participant_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_chat_delegation_requester")
    )
    private ChatParticipant requesterParticipant;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_type", nullable = false, length = 16)
    private ChatDelegationEndType endType;

    @Column(name = "ai_draft_id", length = 32)
    private String aiDraftId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ChatDelegationStatus status;

    @Column(name = "request_id", nullable = false, unique = true)
    private UUID requestId;

    @Column(name = "context_message_id", nullable = false)
    private Long contextMessageId;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "next_action_at")
    private LocalDateTime nextActionAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public ChatDelegation(ChatRoom chatRoom, ChatParticipant requesterParticipant,
        ChatDelegationEndType endType, UUID requestId, Long contextMessageId) {
        this.chatRoom = Objects.requireNonNull(chatRoom);
        this.requesterParticipant = Objects.requireNonNull(requesterParticipant);
        if (!Objects.equals(chatRoom.getId(), requesterParticipant.getChatRoomId())) {
            throw new IllegalArgumentException();
        }
        this.endType = Objects.requireNonNull(endType);
        this.requestId = Objects.requireNonNull(requestId);
        this.contextMessageId = Objects.requireNonNull(contextMessageId);
        this.status = ChatDelegationStatus.DRAFT_GENERATING;
    }

    public void completeDraft(UUID completedRequestId, String aiDraftId) {
        requireGeneratingRequest(completedRequestId);
        this.aiDraftId = Objects.requireNonNull(aiDraftId);
        status = ChatDelegationStatus.AWAITING_CONFIRMATION;
    }

    public void failDraft(UUID failedRequestId) {
        requireGeneratingRequest(failedRequestId);
        status = ChatDelegationStatus.DRAFT_FAILED;
    }

    public void regenerate(
        ChatDelegationEndType newEndType, UUID newRequestId, Long newContextMessageId) {
        if (status != ChatDelegationStatus.AWAITING_CONFIRMATION
            && status != ChatDelegationStatus.DRAFT_FAILED) {
            throw new IllegalStateException();
        }
        endType = Objects.requireNonNull(newEndType);
        aiDraftId = null;
        requestId = Objects.requireNonNull(newRequestId);
        contextMessageId = Objects.requireNonNull(newContextMessageId);
        status = ChatDelegationStatus.DRAFT_GENERATING;
    }

    public void start(String confirmedDraftId, LocalDateTime approvedAt,
        LocalDateTime nextActionAt) {
        if (status != ChatDelegationStatus.AWAITING_CONFIRMATION
            || !Objects.equals(aiDraftId, confirmedDraftId)) {
            throw new IllegalStateException();
        }
        this.approvedAt = Objects.requireNonNull(approvedAt);
        this.nextActionAt = Objects.requireNonNull(nextActionAt);
        status = ChatDelegationStatus.IN_PROGRESS;
    }

    public void reschedule(LocalDateTime nextActionAt) {
        if (status != ChatDelegationStatus.IN_PROGRESS) {
            throw new IllegalStateException();
        }
        this.nextActionAt = Objects.requireNonNull(nextActionAt);
    }

    public void beginGeneration() {
        requireInProgress();
        nextActionAt = null;
    }

    public void requireRetry() {
        requireInProgress();
        status = ChatDelegationStatus.RETRY_REQUIRED;
    }

    public void fail() {
        requireInProgress();
        status = ChatDelegationStatus.FAILED;
    }

    private void requireInProgress() {
        if (status != ChatDelegationStatus.IN_PROGRESS) {
            throw new IllegalStateException();
        }
    }

    private void requireGeneratingRequest(UUID expectedRequestId) {
        if (status != ChatDelegationStatus.DRAFT_GENERATING
            || !requestId.equals(expectedRequestId)) {
            throw new IllegalStateException();
        }
    }

}
