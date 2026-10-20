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
            return from(attempt, attempt.getAttemptNo());
        }

        private static Attempt from(FaceVerificationAttempt attempt, int attemptNo) {
            return new Attempt(attemptNo, attempt.getStatus(), attempt.getDecision(),
                List.copyOf(attempt.getReasonCodes()), attempt.getErrorCode());
        }
    }

    public static FaceVerificationResponse from(
        FaceVerificationSession session, FaceVerificationAttempt attempt, List<FaceVerificationAttempt> attempts) {
        int attemptsUsed = Math.min(session.getAttemptsUsed(), FaceVerificationSession.MAX_ATTEMPTS);
        boolean legacyAttemptOverflow = session.getAttemptsUsed() > FaceVerificationSession.MAX_ATTEMPTS;
        VerificationSessionStatus status = session.getStatus();
        List<Attempt> visibleAttempts;
        Attempt visibleAttempt;
        if (legacyAttemptOverflow) {
            if (status == VerificationSessionStatus.VERIFIED && !attempts.isEmpty()) {
                List<Attempt> firstAttempts = attempts.stream()
                    .limit(FaceVerificationSession.MAX_ATTEMPTS - 1L)
                    .map(Attempt::from)
                    .toList();
                FaceVerificationAttempt successfulAttempt = attempt != null
                    && attempt.getDecision() == VerificationDecision.VERIFIED
                        ? attempt
                        : attempts.getLast();
                visibleAttempts = java.util.stream.Stream.concat(firstAttempts.stream(),
                    java.util.stream.Stream.of(Attempt.from(successfulAttempt, FaceVerificationSession.MAX_ATTEMPTS)))
                    .toList();
                visibleAttempt = attempt != null
                    && attempt.getAttemptNo() < FaceVerificationSession.MAX_ATTEMPTS
                        ? Attempt.from(attempt)
                        : visibleAttempts.getLast();
            } else {
                status = VerificationSessionStatus.EXHAUSTED;
                visibleAttempts = attempts.stream()
                    .limit(FaceVerificationSession.MAX_ATTEMPTS)
                    .map(Attempt::from)
                    .toList();
                visibleAttempt = visibleAttempts.isEmpty() ? null : visibleAttempts.getLast();
            }
        } else {
            visibleAttempts = attempts.stream().map(Attempt::from).toList();
            visibleAttempt = attempt == null ? null : Attempt.from(attempt);
        }
        boolean ready = status == VerificationSessionStatus.READY;
        int challengeAttemptNo = ready
            ? Math.min(attemptsUsed + 1, FaceVerificationSession.MAX_ATTEMPTS)
            : Math.max(1, attemptsUsed);
        return new FaceVerificationResponse(
            session.getId(), session.getUploadIntentId(), status,
            attemptsUsed, FaceVerificationSession.MAX_ATTEMPTS,
            FaceVerificationSession.MAX_ATTEMPTS - attemptsUsed,
            ready ? attemptsUsed + 1 : null, ready,
            session.challengesForAttempt(challengeAttemptNo),
            session.getExpiresAt().atZone(ZoneId.systemDefault()).toInstant(),
            visibleAttempt, visibleAttempts);
    }
}
