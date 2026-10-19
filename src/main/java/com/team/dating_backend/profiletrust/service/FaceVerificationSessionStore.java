package com.team.dating_backend.profiletrust.service;

import com.team.dating_backend.file.entity.FileUploadIntent;
import com.team.dating_backend.file.enums.FileUploadIntentStatus;
import com.team.dating_backend.file.repository.FileUploadIntentRepository;
import com.team.dating_backend.onboarding.service.OnboardingCompletionService;
import com.team.dating_backend.profiletrust.dto.ai.FaceVerificationAiPayloads.ChallengeResponse;
import com.team.dating_backend.profiletrust.dto.ai.FaceVerificationAiPayloads.VerificationResponse;
import com.team.dating_backend.profiletrust.dto.response.FaceVerificationResponse;
import com.team.dating_backend.profiletrust.entity.FaceVerificationAttempt;
import com.team.dating_backend.profiletrust.entity.FaceVerificationSession;
import com.team.dating_backend.profiletrust.enums.ProfileTrustErrorCode;
import com.team.dating_backend.profiletrust.enums.VerificationAttemptStatus;
import com.team.dating_backend.profiletrust.enums.VerificationSessionStatus;
import com.team.dating_backend.profiletrust.exception.ProfileTrustBusinessException;
import com.team.dating_backend.profiletrust.repository.FaceVerificationAttemptRepository;
import com.team.dating_backend.profiletrust.repository.FaceVerificationSessionRepository;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = ProfileTrustBusinessException.class)
public class FaceVerificationSessionStore {

    private final FaceVerificationSessionRepository sessionRepository;
    private final FaceVerificationAttemptRepository attemptRepository;
    private final FileUploadIntentRepository uploadIntentRepository;
    private final UserRepository userRepository;
    private final OnboardingCompletionService onboardingCompletionService;

    public record Admission(FaceVerificationSession session, FaceVerificationResponse previousResult) {

        public boolean acquired() {
            return previousResult == null;
        }
    }

    public FaceVerificationResponse create(
        Long ownerUserId, Long uploadIntentId, String versionToken, ChallengeResponse challenge) {
        userRepository.findByIdForUpdate(ownerUserId).orElseThrow(
            () -> failure(ProfileTrustErrorCode.VERIFICATION_USER_NOT_FOUND));
        LocalDateTime now = LocalDateTime.now();
        FileUploadIntent photo = uploadIntentRepository.findOwned(uploadIntentId, ownerUserId)
            .orElseThrow(() -> failure(ProfileTrustErrorCode.PRIMARY_PHOTO_NOT_FOUND));
        if (!photo.getExpiresAt().isAfter(now)) {
            throw failure(ProfileTrustErrorCode.PRIMARY_PHOTO_EXPIRED);
        }
        if (photo.getStatus() != FileUploadIntentStatus.PENDING
            || !Objects.equals(versionToken, photo.getValidatedVersionToken())) {
            throw failure(ProfileTrustErrorCode.PRIMARY_PHOTO_NOT_READY);
        }

        FaceVerificationSession latest = sessionRepository.findFirstByOwnerUserIdOrderByIdDesc(ownerUserId)
            .orElse(null);
        if (latest != null) {
            expire(latest, now);
            boolean samePhoto = latest.getUploadIntentId().equals(uploadIntentId)
                && latest.getPhotoVersionToken().equals(versionToken);
            if (samePhoto && (latest.getStatus().isOpen()
                || latest.getStatus() == VerificationSessionStatus.VERIFIED)) {
                return response(latest);
            }
            if (latest.getStatus() == VerificationSessionStatus.PROCESSING) {
                throw failure(ProfileTrustErrorCode.VERIFICATION_IN_PROGRESS);
            }
            if (latest.getStatus().isOpen()) {
                latest.invalidate();
            }
        }

        LocalDateTime expiresAt = challenge.expiresAt().atZoneSameInstant(ZoneId.systemDefault())
            .toLocalDateTime();
        if (photo.getExpiresAt().isBefore(expiresAt)) {
            expiresAt = photo.getExpiresAt();
        }
        if (!expiresAt.isAfter(now)) {
            throw failure(ProfileTrustErrorCode.VERIFICATION_SESSION_EXPIRED);
        }
        FaceVerificationSession session = sessionRepository.saveAndFlush(FaceVerificationSession.create(
            ownerUserId, uploadIntentId, versionToken, challenge.challengeToken(), now, expiresAt));
        return response(session);
    }

    public FaceVerificationSession load(Long ownerUserId, Long sessionId) {
        FaceVerificationSession session = owned(ownerUserId, sessionId);
        expire(session, LocalDateTime.now());
        return session;
    }

    public FaceVerificationResponse get(Long ownerUserId, Long sessionId) {
        return response(load(ownerUserId, sessionId));
    }

    public Admission claim(
        Long ownerUserId, Long sessionId, int attemptNo, String videoHash, String contentType) {
        FaceVerificationSession session = load(ownerUserId, sessionId);
        FaceVerificationAttempt existing = attemptRepository.findBySessionIdAndAttemptNo(sessionId, attemptNo)
            .orElse(null);
        if (existing != null) {
            if (!existing.getVideoHash().equals(videoHash) || !existing.getContentType().equals(contentType)) {
                throw failure(ProfileTrustErrorCode.VERIFICATION_ATTEMPT_CONFLICT);
            }
            if (existing.getStatus() == VerificationAttemptStatus.PROCESSING) {
                throw failure(ProfileTrustErrorCode.VERIFICATION_IN_PROGRESS);
            }
            return new Admission(session, response(session, existing));
        }
        requireReady(session);
        if (attemptNo != session.getAttemptsUsed() + 1) {
            throw failure(ProfileTrustErrorCode.VERIFICATION_ATTEMPT_OUT_OF_ORDER);
        }
        if (attemptRepository.existsBySessionIdAndVideoHash(sessionId, videoHash)) {
            throw failure(ProfileTrustErrorCode.VERIFICATION_DUPLICATE_VIDEO);
        }
        session.beginAttempt();
        attemptRepository.saveAndFlush(FaceVerificationAttempt.create(
            sessionId, attemptNo, videoHash, contentType, LocalDateTime.now()));
        return new Admission(session, null);
    }

    public FaceVerificationResponse finish(
        Long ownerUserId, Long sessionId, int attemptNo, VerificationResponse result) {
        FaceVerificationSession session = load(ownerUserId, sessionId);
        FaceVerificationAttempt attempt = attempt(sessionId, attemptNo);
        if (session.getStatus() == VerificationSessionStatus.PROCESSING
            && attempt.getStatus() == VerificationAttemptStatus.PROCESSING
            && attemptNo == session.getAttemptsUsed()) {
            attempt.complete(result.decision(), result.reasonCodes(), LocalDateTime.now());
            session.complete(result.decision());
            onboardingCompletionService.activateIfCompleted(ownerUserId);
        }
        return response(session, attempt);
    }

    public void fail(Long ownerUserId, Long sessionId, int attemptNo, ProfileTrustErrorCode errorCode) {
        FaceVerificationSession session = load(ownerUserId, sessionId);
        FaceVerificationAttempt attempt = attempt(sessionId, attemptNo);
        if (session.getStatus() != VerificationSessionStatus.PROCESSING
            || attempt.getStatus() != VerificationAttemptStatus.PROCESSING
            || attemptNo != session.getAttemptsUsed()) {
            return;
        }
        attempt.fail(errorCode, LocalDateTime.now());
        if (errorCode == ProfileTrustErrorCode.PRIMARY_PHOTO_CHANGED
            || errorCode == ProfileTrustErrorCode.PRIMARY_PHOTO_NOT_READY
            || errorCode == ProfileTrustErrorCode.PRIMARY_PHOTO_EXPIRED
            || errorCode == ProfileTrustErrorCode.PRIMARY_PHOTO_NOT_FOUND
            || errorCode == ProfileTrustErrorCode.VERIFICATION_CHALLENGE_INVALID) {
            session.invalidate();
        } else {
            session.fail();
        }
    }

    private void expire(FaceVerificationSession session, LocalDateTime now) {
        session.expireIfNecessary(now);
        if (session.getStatus() == VerificationSessionStatus.EXPIRED) {
            attemptRepository.findFirstBySessionIdOrderByAttemptNoDesc(session.getId())
                .filter(attempt -> attempt.getStatus() == VerificationAttemptStatus.PROCESSING)
                .ifPresent(attempt -> attempt.fail(ProfileTrustErrorCode.VERIFICATION_SESSION_EXPIRED, now));
        }
    }

    private FaceVerificationSession owned(Long ownerUserId, Long sessionId) {
        return sessionRepository.findOwnedForUpdate(sessionId, ownerUserId)
            .orElseThrow(() -> failure(ProfileTrustErrorCode.VERIFICATION_SESSION_NOT_FOUND));
    }

    private FaceVerificationAttempt attempt(Long sessionId, int attemptNo) {
        return attemptRepository.findBySessionIdAndAttemptNo(sessionId, attemptNo)
            .orElseThrow(() -> failure(ProfileTrustErrorCode.VERIFICATION_ATTEMPT_OUT_OF_ORDER));
    }

    private FaceVerificationResponse response(FaceVerificationSession session) {
        return response(session, null);
    }

    private FaceVerificationResponse response(FaceVerificationSession session, FaceVerificationAttempt attempt) {
        List<FaceVerificationAttempt> attempts = attemptRepository
            .findAllBySessionIdOrderByAttemptNoAsc(session.getId());
        FaceVerificationAttempt selected = attempt != null || attempts.isEmpty() ? attempt : attempts.getLast();
        return FaceVerificationResponse.from(session, selected, attempts);
    }

    private void requireReady(FaceVerificationSession session) {
        switch (session.getStatus()) {
            case READY -> {}
            case PROCESSING -> throw failure(ProfileTrustErrorCode.VERIFICATION_IN_PROGRESS);
            case EXPIRED -> throw failure(ProfileTrustErrorCode.VERIFICATION_SESSION_EXPIRED);
            case EXHAUSTED -> throw failure(ProfileTrustErrorCode.VERIFICATION_ATTEMPT_LIMIT_REACHED);
            default -> throw failure(ProfileTrustErrorCode.VERIFICATION_SESSION_CLOSED);
        }
    }

    private ProfileTrustBusinessException failure(ProfileTrustErrorCode code) {
        return new ProfileTrustBusinessException(code);
    }
}
