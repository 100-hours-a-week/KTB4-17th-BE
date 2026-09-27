package com.team.dating_backend.aipractice.entity;

import com.team.dating_backend.aipractice.enums.AiPracticeSessionStatus;
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
@Table(
    name = "ai_practice_sessions",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_ai_practice_session_ai_session_id",
        columnNames = "ai_session_id"
    ),
    indexes = @Index(
        name = "idx_ai_practice_session_user_target_status",
        columnList = "user_id,target_member_id,status"
    )
)
public class AiPracticeSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "user_id",
        nullable = false,
        updatable = false
    )
    private Long userId;

    @Column(
        name = "target_member_id",
        updatable = false
    )
    private Long targetMemberId;

    @Column(
        name = "ai_session_id",
        length = 100
    )
    private String aiSessionId;

    @Enumerated(EnumType.STRING)
    @Column(
        name = "status",
        nullable = false,
        length = 20
    )
    private AiPracticeSessionStatus status;

    @Column(
        name = "started_at",
        nullable = false,
        updatable = false
    )
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(
        name = "end_command_enqueued",
        nullable = false
    )
    private boolean endCommandEnqueued;

    public AiPracticeSession(Long userId, Long targetMemberId, LocalDateTime startedAt) {
        this.userId = userId;
        this.targetMemberId = targetMemberId;
        this.status = AiPracticeSessionStatus.ACTIVE;
        this.startedAt = startedAt;
        this.endCommandEnqueued = false;
    }

    public void end(LocalDateTime endedAt) {
        if (status == AiPracticeSessionStatus.ACTIVE) {
            this.status = AiPracticeSessionStatus.ENDED;
            this.endedAt = endedAt;
        }
    }

    public void attachAiSessionId(String aiSessionId) {
        if (aiSessionId == null || aiSessionId.isBlank()) {
            return;
        }
        if (this.aiSessionId != null && !this.aiSessionId.equals(aiSessionId)) {
            throw new IllegalStateException();
        }
        this.aiSessionId = aiSessionId;
    }

    public boolean markEndCommandEnqueued() {
        if (endCommandEnqueued || aiSessionId == null) {
            return false;
        }
        this.endCommandEnqueued = true;
        return true;
    }
}
