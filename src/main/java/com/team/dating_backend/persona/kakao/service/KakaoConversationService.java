package com.team.dating_backend.persona.kakao.service;

import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.onboarding.exception.UserNotFoundException;
import com.team.dating_backend.persona.enums.PersonaErrorCode;
import com.team.dating_backend.persona.exception.PersonaBusinessException;
import com.team.dating_backend.persona.kakao.client.KakaoConversationAiClient;
import com.team.dating_backend.persona.kakao.dto.KakaoSubmissionClaim;
import com.team.dating_backend.persona.kakao.dto.KakaoSubmissionSnapshot;
import com.team.dating_backend.persona.kakao.dto.ai.KakaoAiPayloads.JobCreated;
import com.team.dating_backend.persona.kakao.dto.ai.KakaoAiPayloads.JobResponse;
import com.team.dating_backend.persona.kakao.dto.response.KakaoConversationInspectResponse;
import com.team.dating_backend.persona.kakao.dto.response.KakaoConversationParticipantResponse;
import com.team.dating_backend.persona.kakao.dto.response.KakaoConversationSubmissionResponse;
import com.team.dating_backend.persona.kakao.enums.KakaoConversationErrorCode;
import com.team.dating_backend.persona.kakao.enums.KakaoSubmissionStatus;
import com.team.dating_backend.persona.kakao.exception.KakaoConversationBusinessException;
import com.team.dating_backend.persona.kakao.parser.KakaoConversationParser;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.PersonaOnboardingStatus;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class KakaoConversationService {

    private final UserRepository userRepository;
    private final KakaoConversationFileReader fileReader;
    private final KakaoConversationParser parser;
    private final KakaoConversationAiClient aiClient;
    private final KakaoSubmissionPersistenceService persistence;

    public KakaoConversationInspectResponse inspect(Long userId, MultipartFile file) {
        User user = requireUser(userId);
        List<KakaoConversationParticipantResponse> participants = parser.parse(fileReader.read(file));
        String selected = automaticSpeaker(user, participants);
        return new KakaoConversationInspectResponse(participants, selected, selected == null);
    }

    public KakaoConversationSubmissionResponse submit(Long userId, String idempotencyKey,
        MultipartFile file, String speakerName) {
        String requestKey = uuid(idempotencyKey);
        User user = requireUser(userId);
        if (user.getPersonaOnboardingStatus() != PersonaOnboardingStatus.CONFIRMED) {
            throw failure(KakaoConversationErrorCode.PERSONA_CONFIRMATION_REQUIRED);
        }
        byte[] bytes = fileReader.read(file);
        List<KakaoConversationParticipantResponse> participants = parser.parse(bytes);
        String selected = selectSpeaker(user, participants, speakerName);
        KakaoSubmissionClaim claim = persistence.claim(userId, requestKey, fingerprint(bytes, selected));
        if (!claim.acquired()) {
            if (claim.submission().submissionStatus() == KakaoSubmissionStatus.REJECTED) {
                throw failure(KakaoConversationErrorCode.valueOf(claim.submission().errorCode()));
            }
            return response(claim.submission());
        }
        String submissionId = claim.submission().id();
        try {
            aiClient.requireConfigured();
            JobCreated created = aiClient.submit(userId, selected, fileReader.filename(file), bytes);
            return response(persistence.accept(userId, submissionId, created.jobId()));
        } catch (KakaoConversationBusinessException exception) {
            KakaoConversationErrorCode code = (KakaoConversationErrorCode) exception.getErrorCode();
            boolean uncertain = code == KakaoConversationErrorCode.AI_SUBMISSION_OUTCOME_UNKNOWN
                || code == KakaoConversationErrorCode.AI_SERVER_RESPONSE_INVALID;
            KakaoSubmissionSnapshot rejected = persistence.reject(userId, submissionId,
                uncertain ? KakaoConversationErrorCode.AI_SUBMISSION_OUTCOME_UNKNOWN : code, uncertain);
            if (uncertain) {
                return response(rejected);
            }
            throw exception;
        }
    }

    public KakaoConversationSubmissionResponse getSubmission(Long userId, String submissionId) {
        requireUser(userId);
        KakaoSubmissionSnapshot submission = persistence.findOwned(userId, uuid(submissionId));
        if (submission.submissionStatus() != KakaoSubmissionStatus.SUBMITTED || submission.isJobFinished()) {
            return response(submission);
        }
        JobResponse job = aiClient.getJob(submission.aiJobId());
        return response(persistence.updateJob(userId, submission.id(), job.status(), job.personaId(),
            job.analyzedCount(), "failed".equals(job.status())
                ? KakaoConversationErrorCode.AI_EXTRACTION_FAILED.name()
                : null,
            job.finishedAtUtc()));
    }

    private User requireUser(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        if (user.getStatus() != UserStatus.ONBOARDING && user.getStatus() != UserStatus.ACTIVE) {
            throw new PersonaBusinessException(PersonaErrorCode.ACTION_NOT_ALLOWED);
        }
        return user;
    }

    private String selectSpeaker(User user, List<KakaoConversationParticipantResponse> participants, String requested) {
        String selected = requested == null ? automaticSpeaker(user, participants) : requested;
        if (selected == null) {
            throw failure(KakaoConversationErrorCode.SPEAKER_SELECTION_REQUIRED);
        }
        if (participants.stream().noneMatch(participant -> participant.name().equals(selected))) {
            throw failure(KakaoConversationErrorCode.SPEAKER_NOT_FOUND);
        }
        if (!parser.isSelectable(selected)) {
            throw failure(KakaoConversationErrorCode.SPEAKER_NAME_NOT_SUPPORTED);
        }
        return selected;
    }

    private String automaticSpeaker(User user, List<KakaoConversationParticipantResponse> participants) {
        return participants.stream().map(KakaoConversationParticipantResponse::name)
            .filter(name -> name.equals(user.getName()) && parser.isSelectable(name))
            .findFirst().orElse(null);
    }

    private String uuid(String value) {
        try {
            if (value == null || value.length() != 36) {
                throw new RequestValidationException();
            }
            String normalized = UUID.fromString(value).toString();
            if (!normalized.equalsIgnoreCase(value)) {
                throw new RequestValidationException();
            }
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw new RequestValidationException();
        }
    }

    private String fingerprint(byte[] bytes, String speaker) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] fileHash = digest.digest(bytes);
            digest.update(fileHash);
            digest.update(speaker.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private KakaoConversationSubmissionResponse response(KakaoSubmissionSnapshot submission) {
        String status = switch (submission.submissionStatus()) {
            case SUBMITTING -> "submitting";
            case SUBMITTED -> submission.jobStatus();
            case REJECTED -> "rejected";
            case UNKNOWN -> "submission_unknown";
        };
        return new KakaoConversationSubmissionResponse(submission.id(), status, submission.personaId(),
            submission.analyzedCount(), submission.errorCode(), submission.createdAt().atOffset(ZoneOffset.UTC),
            submission.finishedAt() == null ? null : submission.finishedAt().atOffset(ZoneOffset.UTC));
    }

    private KakaoConversationBusinessException failure(KakaoConversationErrorCode code) {
        return new KakaoConversationBusinessException(code);
    }
}
