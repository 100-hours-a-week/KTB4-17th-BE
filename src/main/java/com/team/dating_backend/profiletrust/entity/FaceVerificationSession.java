package com.team.dating_backend.profiletrust.entity;

import com.team.dating_backend.profiletrust.enums.ProfileTrustErrorCode;
import com.team.dating_backend.profiletrust.enums.VerificationDecision;
import com.team.dating_backend.profiletrust.enums.VerificationSessionStatus;
import com.team.dating_backend.profiletrust.exception.ProfileTrustBusinessException;
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
@Table(
    name = "face_verification_sessions", indexes = {
        @Index(name = "idx_face_verification_owner_id", columnList = "owner_user_id,id")
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FaceVerificationSession {

    public static final int MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private Long ownerUserId;

    @Column(name = "upload_intent_id", nullable = false, updatable = false)
    private Long uploadIntentId;

    @Column(name = "photo_version_token", nullable = false, updatable = false, length = 255)
    private String photoVersionToken;

    @Column(name = "verified_file_id")
    private Long verifiedFileId;

    @Column(name = "challenge_token", nullable = false, updatable = false, length = 2048)
    private String challengeToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VerificationSessionStatus status;

    @Column(name = "attempts_used", nullable = false)
    private int attemptsUsed;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    public static FaceVerificationSession create(
        Long ownerUserId, Long uploadIntentId, String photoVersionToken,
        String challengeToken, LocalDateTime createdAt, LocalDateTime expiresAt) {
        FaceVerificationSession session = new FaceVerificationSession();
        session.ownerUserId = ownerUserId;
        session.uploadIntentId = uploadIntentId;
        session.photoVersionToken = photoVersionToken;
        session.challengeToken = challengeToken;
        session.createdAt = createdAt;
        session.expiresAt = expiresAt;
        session.status = VerificationSessionStatus.READY;
        return session;
    }

    public int beginAttempt() {
        if (status != VerificationSessionStatus.READY || attemptsUsed >= MAX_ATTEMPTS) {
            throw new ProfileTrustBusinessException(ProfileTrustErrorCode.VERIFICATION_SESSION_CLOSED);
        }
        attemptsUsed++;
        status = VerificationSessionStatus.PROCESSING;
        return attemptsUsed;
    }

    public void complete(VerificationDecision decision) {
        requireProcessing();
        status = switch (decision) {
            case VERIFIED -> VerificationSessionStatus.VERIFIED;
            case NOT_VERIFIED -> VerificationSessionStatus.NOT_VERIFIED;
            case RETRY -> retryStatus();
        };
    }

    public void fail() {
        requireProcessing();
        status = retryStatus();
    }

    public void expireIfNecessary(LocalDateTime now) {
        if (status.isOpen() && !expiresAt.isAfter(now)) {
            invalidate();
        }
    }

    public void invalidate() {
        status = VerificationSessionStatus.EXPIRED;
    }

    private VerificationSessionStatus retryStatus() {
        return attemptsUsed >= MAX_ATTEMPTS
            ? VerificationSessionStatus.EXHAUSTED
            : VerificationSessionStatus.READY;
    }

    private void requireProcessing() {
        if (status != VerificationSessionStatus.PROCESSING) {
            throw new ProfileTrustBusinessException(ProfileTrustErrorCode.VERIFICATION_SESSION_CLOSED);
        }
    }
}
