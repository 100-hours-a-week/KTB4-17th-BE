package com.team.dating_backend.persona.kakao.entity;

import com.team.dating_backend.persona.kakao.enums.KakaoSubmissionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Only submission metadata is persisted; no file bytes, messages or participant
 * names.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "persona_kakao_submissions",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_kakao_submission_user_key", columnNames = {"user_id", "idempotency_key"}
    )
)
public class KakaoConversationSubmission {

    @Id
    @Column(length = 36)
    private String id;
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;
    @Column(name = "idempotency_key", nullable = false, length = 36, updatable = false)
    private String idempotencyKey;
    @Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
    private String requestFingerprint;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "submission_status", nullable = false, length = 16)
    private KakaoSubmissionStatus submissionStatus;
    @Column(name = "ai_job_id", length = 128)
    private String aiJobId;
    @Column(name = "job_status", length = 16)
    private String jobStatus;
    @Column(name = "persona_id", length = 128)
    private String personaId;
    @Column(name = "analyzed_count", nullable = false)
    private int analyzedCount;
    @Column(name = "error_code", length = 100)
    private String errorCode;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    public void accept(String jobId) {
        // A delayed acceptance may resolve UNKNOWN, but must not replace another
        // accepted job.
        if (submissionStatus == KakaoSubmissionStatus.SUBMITTING
            || submissionStatus == KakaoSubmissionStatus.UNKNOWN) {
            aiJobId = jobId;
            submissionStatus = KakaoSubmissionStatus.SUBMITTED;
            jobStatus = "pending";
            errorCode = null;
        }
    }

    public void reject(String code, boolean uncertain) {
        if (submissionStatus == KakaoSubmissionStatus.SUBMITTING) {
            submissionStatus = uncertain ? KakaoSubmissionStatus.UNKNOWN : KakaoSubmissionStatus.REJECTED;
            errorCode = code;
        }
    }

    public boolean isJobFinished() {
        return "succeeded".equals(jobStatus) || "failed".equals(jobStatus);
    }

    public void updateJob(String status, String resultPersonaId, int count,
        String failureCode, LocalDateTime completedAt) {
        if (submissionStatus != KakaoSubmissionStatus.SUBMITTED || isJobFinished()
            || "running".equals(jobStatus) && "pending".equals(status)) {
            return;
        }
        jobStatus = status;
        personaId = resultPersonaId;
        analyzedCount = count;
        errorCode = failureCode;
        finishedAt = completedAt;
    }
}
