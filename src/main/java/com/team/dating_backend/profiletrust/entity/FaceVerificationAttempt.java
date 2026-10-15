package com.team.dating_backend.profiletrust.entity;

import com.team.dating_backend.profiletrust.enums.ProfileTrustErrorCode;
import com.team.dating_backend.profiletrust.enums.VerificationAttemptStatus;
import com.team.dating_backend.profiletrust.enums.VerificationDecision;
import com.team.dating_backend.profiletrust.enums.VerificationReasonCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@Table(
    name = "face_verification_attempts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_face_verification_attempt_no", columnNames = {"session_id", "attempt_no"}),
        @UniqueConstraint(name = "uk_face_verification_video_hash", columnNames = {"session_id", "video_hash"})
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FaceVerificationAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private Long sessionId;

    @Column(name = "attempt_no", nullable = false, updatable = false)
    private int attemptNo;

    @Column(name = "video_hash", nullable = false, updatable = false, length = 64)
    private String videoHash;

    @Column(name = "content_type", nullable = false, updatable = false, length = 50)
    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VerificationAttemptStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private VerificationDecision decision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reason_codes", nullable = false, columnDefinition = "json")
    private List<VerificationReasonCode> reasonCodes = List.of();

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "error_code", length = 100)
    private ProfileTrustErrorCode errorCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    public static FaceVerificationAttempt create(
        Long sessionId, int attemptNo, String videoHash, String contentType, LocalDateTime now) {
        FaceVerificationAttempt attempt = new FaceVerificationAttempt();
        attempt.sessionId = sessionId;
        attempt.attemptNo = attemptNo;
        attempt.videoHash = videoHash;
        attempt.contentType = contentType;
        attempt.status = VerificationAttemptStatus.PROCESSING;
        attempt.createdAt = now;
        return attempt;
    }

    public void complete(VerificationDecision decision, List<VerificationReasonCode> reasons, LocalDateTime now) {
        this.status = VerificationAttemptStatus.COMPLETED;
        this.decision = decision;
        this.reasonCodes = List.copyOf(reasons);
        this.finishedAt = now;
    }

    public void fail(ProfileTrustErrorCode errorCode, LocalDateTime now) {
        this.status = VerificationAttemptStatus.ERROR;
        this.errorCode = errorCode;
        this.finishedAt = now;
    }
}
