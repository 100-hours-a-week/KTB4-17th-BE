package com.team.dating_backend.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "chat_message_outbox", uniqueConstraints = @UniqueConstraint(name = "uk_chat_message_outbox_message", columnNames = "chat_message_id"), indexes = @Index(name = "idx_chat_message_outbox_due", columnList = "published_at,failed_at,next_attempt_at,id"))
public class ChatMessageOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_message_id", nullable = false, updatable = false)
    private Long chatMessageId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "last_failure_type", length = 100)
    private String lastFailureType;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    public ChatMessageOutbox(Long chatMessageId, LocalDateTime createdAt) {
        this.chatMessageId = chatMessageId;
        this.createdAt = createdAt;
        this.nextAttemptAt = createdAt;
        this.failureCount = 0;
    }

    public void scheduleRetry(LocalDateTime nextAttemptAt, String failureType) {
        this.failureCount++;
        this.nextAttemptAt = nextAttemptAt;
        this.lastFailureType = failureType;
    }

    public void markFailed(LocalDateTime failedAt, String failureType) {
        this.failureCount++;
        this.failedAt = failedAt;
        this.lastFailureType = failureType;
    }

    public void markPublished(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
        this.lastFailureType = null;
    }
}
