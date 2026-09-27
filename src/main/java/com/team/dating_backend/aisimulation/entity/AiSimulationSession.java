package com.team.dating_backend.aisimulation.entity;

import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "ai_simulation_sessions",
    indexes = @Index(
        name = "idx_ai_simulation_session_user_status_id",
        columnList = "user_id,status,id"
    )
)
public class AiSimulationSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AiSimulationSessionStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    public AiSimulationSession(Long userId, LocalDateTime createdAt) {
        this.userId = userId;
        this.status = AiSimulationSessionStatus.IN_PROGRESS;
        this.createdAt = createdAt;
    }

    public void complete(LocalDateTime finishedAt) {
        requireInProgress();
        this.status = AiSimulationSessionStatus.COMPLETED;
        this.finishedAt = finishedAt;
    }

    public void fail(LocalDateTime finishedAt) {
        if (status == AiSimulationSessionStatus.IN_PROGRESS) {
            this.status = AiSimulationSessionStatus.FAILED;
            this.finishedAt = finishedAt;
        }
    }

    private void requireInProgress() {
        if (status != AiSimulationSessionStatus.IN_PROGRESS) {
            throw new IllegalStateException("AI simulation is not in progress");
        }
    }
}
