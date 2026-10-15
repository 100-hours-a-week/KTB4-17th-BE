package com.team.dating_backend.profiletrust.dto.response;

import com.team.dating_backend.profiletrust.entity.FaceVerificationAttempt;
import com.team.dating_backend.profiletrust.entity.FaceVerificationSession;
import com.team.dating_backend.profiletrust.enums.ProfileTrustErrorCode;
import com.team.dating_backend.profiletrust.enums.VerificationAttemptStatus;
import com.team.dating_backend.profiletrust.enums.VerificationDecision;
import com.team.dating_backend.profiletrust.enums.VerificationReasonCode;
import com.team.dating_backend.profiletrust.enums.VerificationSessionStatus;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

public record FaceVerificationResponse(
    Long sessionId, Long uploadIntentId, VerificationSessionStatus status,
    int attemptsUsed, int maxAttempts, int remainingAttempts, Integer nextAttemptNo,
    boolean canRetry, List<String> challenges, Instant expiresAt, Attempt attempt) {

    public record Attempt(
        int attemptNo, VerificationAttemptStatus status, VerificationDecision decision,
        List<VerificationReasonCode> reasonCodes, ProfileTrustErrorCode errorCode) {}

    public static FaceVerificationResponse from(
        FaceVerificationSession session, FaceVerificationAttempt attempt) {
        boolean ready = session.getStatus() == VerificationSessionStatus.READY;
        return new FaceVerificationResponse(
            session.getId(), session.getUploadIntentId(), session.getStatus(),
            session.getAttemptsUsed(), FaceVerificationSession.MAX_ATTEMPTS,
            FaceVerificationSession.MAX_ATTEMPTS - session.getAttemptsUsed(),
            ready ? session.getAttemptsUsed() + 1 : null, ready,
            List.of("LOOK_STRAIGHT"), session.getExpiresAt().atZone(ZoneId.systemDefault()).toInstant(),
            attempt == null
                ? null
                : new Attempt(
                    attempt.getAttemptNo(), attempt.getStatus(), attempt.getDecision(),
                    attempt.getReasonCodes(), attempt.getErrorCode()));
    }
}
