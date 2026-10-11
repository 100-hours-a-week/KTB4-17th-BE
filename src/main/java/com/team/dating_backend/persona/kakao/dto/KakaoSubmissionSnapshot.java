package com.team.dating_backend.persona.kakao.dto;

import com.team.dating_backend.persona.kakao.entity.KakaoConversationSubmission;
import com.team.dating_backend.persona.kakao.enums.KakaoSubmissionStatus;
import java.time.LocalDateTime;

public record KakaoSubmissionSnapshot(
    String id, KakaoSubmissionStatus submissionStatus, String aiJobId,
    String jobStatus, String personaId, int analyzedCount, String errorCode,
    LocalDateTime createdAt, LocalDateTime finishedAt) {

    public static KakaoSubmissionSnapshot from(KakaoConversationSubmission submission) {
        return new KakaoSubmissionSnapshot(submission.getId(), submission.getSubmissionStatus(),
            submission.getAiJobId(), submission.getJobStatus(), submission.getPersonaId(),
            submission.getAnalyzedCount(), submission.getErrorCode(),
            submission.getCreatedAt(), submission.getFinishedAt());
    }

    public boolean isJobFinished() {
        return "succeeded".equals(jobStatus) || "failed".equals(jobStatus);
    }
}
