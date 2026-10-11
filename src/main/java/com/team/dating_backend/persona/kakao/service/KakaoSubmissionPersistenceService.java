package com.team.dating_backend.persona.kakao.service;

import com.team.dating_backend.persona.kakao.config.KakaoConversationProperties;
import com.team.dating_backend.persona.kakao.dto.KakaoSubmissionClaim;
import com.team.dating_backend.persona.kakao.dto.KakaoSubmissionSnapshot;
import com.team.dating_backend.persona.kakao.entity.KakaoConversationSubmission;
import com.team.dating_backend.persona.kakao.enums.KakaoConversationErrorCode;
import com.team.dating_backend.persona.kakao.enums.KakaoSubmissionStatus;
import com.team.dating_backend.persona.kakao.exception.KakaoConversationBusinessException;
import com.team.dating_backend.persona.kakao.repository.KakaoConversationSubmissionRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class KakaoSubmissionPersistenceService {

    private final KakaoConversationSubmissionRepository repository;
    private final KakaoConversationProperties properties;

    @Transactional
    public KakaoSubmissionClaim claim(Long userId, String requestKey, String fingerprint) {
        String id = UUID.randomUUID().toString();
        repository.insertIfAbsent(id, userId, requestKey, fingerprint, LocalDateTime.now(ZoneOffset.UTC));
        KakaoConversationSubmission submission = repository.findByUserIdAndIdempotencyKey(userId, requestKey)
            .orElseThrow(() -> new IllegalStateException("Submission claim was not persisted"));
        if (!fingerprint.equals(submission.getRequestFingerprint())) {
            throw new KakaoConversationBusinessException(KakaoConversationErrorCode.KAKAO_IDEMPOTENCY_CONFLICT);
        }
        expireInterruptedSubmission(submission);
        return new KakaoSubmissionClaim(id.equals(submission.getId()), KakaoSubmissionSnapshot.from(submission));
    }

    @Transactional
    public KakaoSubmissionSnapshot findOwned(Long userId, String id) {
        KakaoConversationSubmission submission = requireOwned(userId, id);
        expireInterruptedSubmission(submission);
        return KakaoSubmissionSnapshot.from(submission);
    }

    @Transactional
    public KakaoSubmissionSnapshot accept(Long userId, String id, String jobId) {
        KakaoConversationSubmission submission = requireOwned(userId, id);
        submission.accept(jobId);
        return KakaoSubmissionSnapshot.from(submission);
    }

    @Transactional
    public KakaoSubmissionSnapshot reject(Long userId, String id, KakaoConversationErrorCode code, boolean uncertain) {
        KakaoConversationSubmission submission = requireOwned(userId, id);
        submission.reject(code.name(), uncertain);
        return KakaoSubmissionSnapshot.from(submission);
    }

    @Transactional
    public KakaoSubmissionSnapshot updateJob(Long userId, String id, String status,
        String personaId, int count, String errorCode, LocalDateTime finishedAt) {
        KakaoConversationSubmission submission = requireOwned(userId, id);
        submission.updateJob(status, personaId, count, errorCode, finishedAt);
        return KakaoSubmissionSnapshot.from(submission);
    }

    private KakaoConversationSubmission requireOwned(Long userId, String id) {
        return repository.findByIdAndUserId(id, userId)
            .orElseThrow(
                () -> new KakaoConversationBusinessException(KakaoConversationErrorCode.KAKAO_SUBMISSION_NOT_FOUND));
    }

    private void expireInterruptedSubmission(KakaoConversationSubmission submission) {
        long timeoutMs = (long) properties.getConnectTimeoutMs() + properties.getReadTimeoutMs() + 30000;
        if (submission.getSubmissionStatus() == KakaoSubmissionStatus.SUBMITTING
            && submission.getCreatedAt()
                .isBefore(LocalDateTime.now(ZoneOffset.UTC).minusNanos(timeoutMs * 1_000_000))) {
            // The API has no lookup by our idempotency key: a lost reply cannot be retried
            // safely.
            submission.reject(KakaoConversationErrorCode.AI_SUBMISSION_OUTCOME_UNKNOWN.name(), true);
        }
    }
}
