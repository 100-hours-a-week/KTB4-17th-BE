package com.team.dating_backend.aisimulation.entity;

import com.team.dating_backend.aisimulation.enums.AiSimulationReportStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "ai_simulation_reports",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_ai_simulation_report_session_id",
        columnNames = "session_id"
    )
)
public class AiSimulationReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false, updatable = false)
    private AiSimulationSession session;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AiSimulationReportStatus status;

    @Column(name = "overall_score", precision = 5, scale = 2)
    private BigDecimal overallScore;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "conversation_flow_grade", length = 1)
    private String conversationFlowGrade;

    @Column(name = "question_exchange_grade", length = 1)
    private String questionExchangeGrade;

    @Column(name = "interest_expression_grade", length = 1)
    private String interestExpressionGrade;

    @Column(name = "initiative_balance_grade", length = 1)
    private String initiativeBalanceGrade;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_result", columnDefinition = "JSON")
    private Map<String, Object> detailResult;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public AiSimulationReport(AiSimulationSession session, LocalDateTime createdAt) {
        this.session = session;
        this.status = AiSimulationReportStatus.GENERATING;
        this.createdAt = createdAt;
    }

    public void complete(
        int overallScore,
        String summary,
        Map<String, Object> detailResult,
        LocalDateTime completedAt) {
        if (status != AiSimulationReportStatus.GENERATING) {
            throw new IllegalStateException("AI simulation report is not generating");
        }
        this.status = AiSimulationReportStatus.COMPLETED;
        this.overallScore = BigDecimal.valueOf(overallScore);
        this.summary = summary;
        this.detailResult = Map.copyOf(detailResult);
        this.completedAt = completedAt;
    }

    public void fail() {
        if (status == AiSimulationReportStatus.GENERATING) {
            this.status = AiSimulationReportStatus.FAILED;
        }
    }
}
