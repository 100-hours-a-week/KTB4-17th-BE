package com.team.dating_backend.aipractice.entity;

import com.team.dating_backend.aipractice.enums.AiPracticeOutboxCommandType;
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
@Table(
    name = "ai_practice_outbox",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_ai_practice_outbox_idempotency_key",
        columnNames = "idempotency_key"
    ),
    indexes = @Index(
        name = "idx_ai_practice_outbox_due",
        columnList = "published_at,failed_at,next_attempt_at,id"
    )
)
public class AiPracticeOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(
        name = "command_type",
        nullable = false,
        length = 30,
        updatable = false
    )
    private AiPracticeOutboxCommandType commandType;

    @Column(
        name = "session_id",
        nullable = false,
        updatable = false
    )
    private Long sessionId;

    @Column(
        name = "chat_id",
        updatable = false
    )
    private Long chatId;

    @Column(
        name = "generation_attempt",
        nullable = false,
        updatable = false
    )
    private int generationAttempt;

    @Column(
        name = "idempotency_key",
        nullable = false,
        updatable = false
    )
    private UUID idempotencyKey;

    @Column(
        name = "created_at",
        nullable = false,
        updatable = false
    )
    private LocalDateTime createdAt;

    @Column(
        name = "next_attempt_at",
        nullable = false
    )
    private LocalDateTime nextAttemptAt;

    @Column(
        name = "failure_count",
        nullable = false
    )
    private int failureCount;

    @Column(
        name = "last_failure_type",
        length = 100
    )
    private String lastFailureType;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    public AiPracticeOutbox(
        AiPracticeOutboxCommandType commandType,
        Long sessionId,
        Long chatId,
        int generationAttempt,
        LocalDateTime createdAt) {
        this.commandType = commandType;
        this.sessionId = sessionId;
        this.chatId = chatId;
        this.generationAttempt = generationAttempt;
        this.idempotencyKey = UUID.randomUUID();
        this.createdAt = createdAt;
        this.nextAttemptAt = createdAt;
        this.failureCount = 0;
    }

    public void scheduleRetry(LocalDateTime nextAttemptAt, String failureType) {
        this.failureCount++;
        this.nextAttemptAt = nextAttemptAt;
        this.lastFailureType = failureType;
    }

    public void markPublished(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
        this.lastFailureType = null;
    }

    public void markFailed(LocalDateTime failedAt, String failureType) {
        this.failureCount++;
        this.failedAt = failedAt;
        this.lastFailureType = failureType;
    }
}
