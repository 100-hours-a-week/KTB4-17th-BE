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
@Table(
    name = "chat_message_outbox",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_chat_message_outbox_message",
        columnNames = "chat_message_id"
    ),
    indexes = {@Index(
        name = "idx_chat_message_outbox_due",
        columnList = "published_at,failed_at,next_attempt_at,id"
    ), @Index(
        name = "idx_chat_message_outbox_rabbit_due",
        columnList = "rabbit_published_at,rabbit_failed_at,rabbit_next_attempt_at,id"
    )}
)
public class ChatMessageOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_message_id", nullable = false, updatable = false)
    private Long chatMessageId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 기존 컬럼은 Blue가 갱신하며, Green은 새 행의 초기값만 넣는다.
    @Column(name = "next_attempt_at", nullable = false, updatable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "failure_count", nullable = false, updatable = false)
    private int failureCount;

    @Column(name = "last_failure_type", length = 100, updatable = false)
    private String lastFailureType;

    @Column(name = "published_at", updatable = false)
    private LocalDateTime publishedAt;

    @Column(name = "failed_at", updatable = false)
    private LocalDateTime failedAt;

    // Blue의 발행 기록과 분리해 두 발행 경로가 서로 덮어쓰지 않게 한다.
    @Column(name = "rabbit_published_at")
    private LocalDateTime rabbitPublishedAt;

    @Column(name = "rabbit_next_attempt_at", nullable = false)
    private LocalDateTime rabbitNextAttemptAt;

    @Column(name = "rabbit_failure_count", nullable = false)
    private int rabbitFailureCount;

    @Column(name = "rabbit_last_failure_type", length = 100)
    private String rabbitLastFailureType;

    @Column(name = "rabbit_failed_at")
    private LocalDateTime rabbitFailedAt;

    // 선점 값은 JDBC 서비스가 관리하므로 엔티티 저장 시 덮어쓰지 않는다.
    @Column(
        name = "rabbit_claim_token", length = 36, columnDefinition = "char(36)",
        insertable = false, updatable = false
    )
    private String rabbitClaimToken;

    @Column(name = "rabbit_claim_until", insertable = false, updatable = false)
    private LocalDateTime rabbitClaimUntil;

    public ChatMessageOutbox(Long chatMessageId, LocalDateTime createdAt) {
        this.chatMessageId = chatMessageId;
        this.createdAt = createdAt;
        this.nextAttemptAt = createdAt;
        this.failureCount = 0;
        this.rabbitNextAttemptAt = createdAt;
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

    public void scheduleRabbitRetry(LocalDateTime nextAttemptAt, String failureType) {
        this.rabbitFailureCount++;
        this.rabbitNextAttemptAt = nextAttemptAt;
        this.rabbitLastFailureType = failureType;
    }

    public void markRabbitFailed(LocalDateTime failedAt, String failureType) {
        this.rabbitFailureCount++;
        this.rabbitFailedAt = failedAt;
        this.rabbitLastFailureType = failureType;
    }

    public void markRabbitPublished(LocalDateTime publishedAt) {
        this.rabbitPublishedAt = publishedAt;
        this.rabbitLastFailureType = null;
    }
}
