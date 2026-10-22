package com.team.dating_backend.chat.delegation.entity;

import com.team.dating_backend.chat.delegation.enums.ChatDelegationMessageStatus;
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
@Table(name = "chat_delegation_messages")
public class ChatDelegationMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "delegation_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_chat_delegation_message_delegation")
    )
    private ChatDelegation delegation;

    @Column(name = "turn", nullable = false)
    private int turn;

    @Column(name = "request_id", nullable = false, unique = true)
    private UUID requestId;

    @Column(name = "context_message_id")
    private Long contextMessageId;

    @Column(name = "ai_message_id")
    private Long aiMessageId;

    @Column(name = "content", length = 1000)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ChatDelegationMessageStatus status;

    @Column(name = "generation_failure_count", nullable = false)
    private int generationFailureCount;

    @Column(name = "transport_retry_count", nullable = false)
    private int transportRetryCount;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public ChatDelegationMessage(ChatDelegation delegation, int turn, UUID requestId,
        Long contextMessageId) {
        this.delegation = Objects.requireNonNull(delegation);
        if (turn < 1 || turn > 3) {
            throw new IllegalArgumentException();
        }
        this.turn = turn;
        this.requestId = Objects.requireNonNull(requestId);
        this.contextMessageId = Objects.requireNonNull(contextMessageId);
        status = ChatDelegationMessageStatus.GENERATING;
    }

    public void resume(Long contextMessageId, LocalDateTime now) {
        if (status != ChatDelegationMessageStatus.RETRY_WAIT
            && status != ChatDelegationMessageStatus.GENERATING) {
            throw new IllegalStateException();
        }
        if (this.contextMessageId == null) {
            this.contextMessageId = Objects.requireNonNull(contextMessageId);
        }
        status = ChatDelegationMessageStatus.GENERATING;
        nextAttemptAt = null;
        updatedAt = Objects.requireNonNull(now);
    }

    public void ready(Long aiMessageId, String content) {
        requireGenerating();
        this.aiMessageId = Objects.requireNonNull(aiMessageId);
        this.content = Objects.requireNonNull(content);
        status = ChatDelegationMessageStatus.READY;
    }

    public boolean retryGeneration(LocalDateTime nextAttemptAt) {
        requireGenerating();
        generationFailureCount++;
        if (generationFailureCount >= 3) {
            status = ChatDelegationMessageStatus.FAILED;
            return false;
        }
        requestId = UUID.randomUUID();
        status = ChatDelegationMessageStatus.RETRY_WAIT;
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt);
        return true;
    }

    public boolean retryTransport(LocalDateTime nextAttemptAt) {
        requireGenerating();
        transportRetryCount++;
        if (transportRetryCount >= 3) {
            status = ChatDelegationMessageStatus.FAILED;
            return false;
        }
        status = ChatDelegationMessageStatus.RETRY_WAIT;
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt);
        return true;
    }

    public void fail() {
        requireGenerating();
        status = ChatDelegationMessageStatus.FAILED;
    }

    private void requireGenerating() {
        if (status != ChatDelegationMessageStatus.GENERATING) {
            throw new IllegalStateException();
        }
    }
}
