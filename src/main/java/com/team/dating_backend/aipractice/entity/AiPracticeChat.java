package com.team.dating_backend.aipractice.entity;

import com.team.dating_backend.aipractice.enums.AiPracticeChatStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "ai_practice_chats",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_ai_practice_chat_client_message",
        columnNames = {
            "session_id",
            "client_message_id"}
    ),
    indexes = @Index(
        name = "idx_ai_practice_chat_session_id",
        columnList = "session_id,id"
    )
)
public class AiPracticeChat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(
        fetch = FetchType.LAZY,
        optional = false
    )
    @JoinColumn(
        name = "session_id",
        nullable = false,
        updatable = false
    )
    private AiPracticeSession session;

    @Column(
        name = "client_message_id",
        nullable = false,
        updatable = false
    )
    private UUID clientMessageId;

    @Column(
        name = "user_message",
        nullable = false,
        length = 500,
        updatable = false
    )
    private String userMessage;

    @Column(
        name = "ai_response",
        columnDefinition = "TEXT"
    )
    private String aiResponse;

    @Enumerated(EnumType.STRING)
    @Column(
        name = "status",
        nullable = false,
        length = 20
    )
    private AiPracticeChatStatus status;

    @Column(
        name = "is_retry",
        nullable = false
    )
    private boolean retry;

    @Column(
        name = "generation_attempt",
        nullable = false
    )
    private int generationAttempt;

    @Column(
        name = "usage_date",
        nullable = false
    )
    private LocalDate usageDate;

    @Column(
        name = "failure_code",
        length = 100
    )
    private String failureCode;

    @Column(
        name = "created_at",
        nullable = false,
        updatable = false
    )
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public AiPracticeChat(
        AiPracticeSession session,
        UUID clientMessageId,
        String userMessage,
        LocalDate usageDate,
        LocalDateTime createdAt) {
        this.session = session;
        this.clientMessageId = clientMessageId;
        this.userMessage = userMessage;
        this.usageDate = usageDate;
        this.createdAt = createdAt;
        this.status = AiPracticeChatStatus.GENERATING;
        this.retry = false;
        this.generationAttempt = 1;
    }

    public void retry(LocalDate usageDate) {
        this.status = AiPracticeChatStatus.GENERATING;
        this.retry = true;
        this.generationAttempt++;
        this.usageDate = usageDate;
        this.aiResponse = null;
        this.failureCode = null;
        this.completedAt = null;
    }

    public void complete(String aiResponse, LocalDateTime completedAt) {
        this.aiResponse = aiResponse;
        this.status = AiPracticeChatStatus.COMPLETED;
        this.failureCode = null;
        this.completedAt = completedAt;
    }

    public void fail(String failureCode) {
        this.status = AiPracticeChatStatus.FAILED;
        this.failureCode = failureCode;
        this.aiResponse = null;
        this.completedAt = null;
    }
}
