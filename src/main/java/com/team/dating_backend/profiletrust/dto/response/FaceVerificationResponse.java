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
    boolean canRetry, List<String> challenges, Instant expiresAt, Attempt attempt, List<Attempt> attempts) {

    public record Attempt(
        int attemptNo, VerificationAttemptStatus status, VerificationDecision decision,
        List<VerificationReasonCode> reasonCodes, ProfileTrustErrorCode errorCode) {

        private static Attempt from(FaceVerificationAttempt attempt) {
            return new Attempt(attempt.getAttemptNo(), attempt.getStatus(), attempt.getDecision(),
                List.copyOf(attempt.getReasonCodes()), attempt.getErrorCode());
        }
    }

    public static FaceVerificationResponse from(
        FaceVerificationSession session, FaceVerificationAttempt attempt, List<FaceVerificationAttempt> attempts) {
        boolean ready = session.getStatus() == VerificationSessionStatus.READY;
        return new FaceVerificationResponse(
            session.getId(), session.getUploadIntentId(), session.getStatus(),
            session.getAttemptsUsed(), FaceVerificationSession.MAX_ATTEMPTS,
            FaceVerificationSession.MAX_ATTEMPTS - session.getAttemptsUsed(),
            ready ? session.getAttemptsUsed() + 1 : null, ready,
            List.of("LOOK_STRAIGHT"), session.getExpiresAt().atZone(ZoneId.systemDefault()).toInstant(),
            attempt == null ? null : Attempt.from(attempt),
            attempts.stream().map(Attempt::from).toList());
    }
}
