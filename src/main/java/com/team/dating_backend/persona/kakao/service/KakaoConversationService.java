package com.team.dating_backend.persona.kakao.service;

import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.onboarding.exception.UserNotFoundException;
import com.team.dating_backend.persona.enums.PersonaErrorCode;
import com.team.dating_backend.persona.exception.PersonaBusinessException;
import com.team.dating_backend.persona.kakao.client.KakaoConversationAiClient;
import com.team.dating_backend.persona.kakao.dto.KakaoJobToken;
import com.team.dating_backend.persona.kakao.dto.ai.KakaoAiPayloads.JobCreated;
import com.team.dating_backend.persona.kakao.dto.ai.KakaoAiPayloads.JobResponse;
import com.team.dating_backend.persona.kakao.dto.response.KakaoConversationInspectResponse;
import com.team.dating_backend.persona.kakao.dto.response.KakaoConversationParticipantResponse;
import com.team.dating_backend.persona.kakao.dto.response.KakaoExtractionJobResponse;
import com.team.dating_backend.persona.kakao.dto.response.KakaoExtractionResponse;
import com.team.dating_backend.persona.kakao.enums.KakaoConversationErrorCode;
import com.team.dating_backend.persona.kakao.exception.KakaoConversationBusinessException;
import com.team.dating_backend.persona.kakao.parser.KakaoConversationParser;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.PersonaOnboardingStatus;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.ZoneOffset;
import java.util.List;
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
    private final KakaoJobTokenService jobTokenService;

    public KakaoConversationInspectResponse inspect(Long userId, MultipartFile file) {
        User user = requireUser(userId);
        List<KakaoConversationParticipantResponse> participants = parser.parse(fileReader.read(file));
        String selected = automaticSpeaker(user, participants);
        return new KakaoConversationInspectResponse(participants, selected, selected == null);
    }

    public KakaoExtractionResponse extract(Long userId, MultipartFile file, String speakerName) {
        User user = requireUser(userId);
        if (user.getPersonaOnboardingStatus() != PersonaOnboardingStatus.CONFIRMED) {
            throw failure(KakaoConversationErrorCode.PERSONA_CONFIRMATION_REQUIRED);
        }
        byte[] bytes = fileReader.read(file);
        List<KakaoConversationParticipantResponse> participants = parser.parse(bytes);
        String selected = selectSpeaker(user, participants, speakerName);
        JobCreated created = aiClient.submit(userId, selected, fileReader.filename(file), bytes);
        KakaoJobToken token = jobTokenService.issue(userId, created.jobId());
        return new KakaoExtractionResponse(created.jobId(), created.status(), token.value(), token.expiresAt());
    }

    public KakaoExtractionJobResponse getJob(Long userId, String jobId, String jobToken) {
        if (jobId == null || !jobId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new RequestValidationException();
        }
        requireUser(userId);
        jobTokenService.verify(userId, jobId, jobToken);
        JobResponse job = aiClient.getJob(jobId);
        return new KakaoExtractionJobResponse(job.jobId(), job.status(), job.personaId(),
            job.analyzedCount(), "failed".equals(job.status())
                ? KakaoConversationErrorCode.AI_EXTRACTION_FAILED.name()
                : null,
            job.createdAtUtc().atOffset(ZoneOffset.UTC),
            job.finishedAtUtc() == null ? null : job.finishedAtUtc().atOffset(ZoneOffset.UTC));
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

    private KakaoConversationBusinessException failure(KakaoConversationErrorCode code) {
        return new KakaoConversationBusinessException(code);
    }
}
