package com.team.dating_backend.profiletrust.service;

import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.file.entity.FileUploadIntent;
import com.team.dating_backend.file.enums.FileErrorCode;
import com.team.dating_backend.file.enums.FileUploadIntentStatus;
import com.team.dating_backend.file.exception.FileStorageException;
import com.team.dating_backend.file.repository.FileUploadIntentRepository;
import com.team.dating_backend.file.storage.FileStorage;
import com.team.dating_backend.profiletrust.client.FaceVerificationAiClient;
import com.team.dating_backend.profiletrust.dto.ai.FaceVerificationAiPayloads.ChallengeResponse;
import com.team.dating_backend.profiletrust.dto.ai.FaceVerificationAiPayloads.VerificationResponse;
import com.team.dating_backend.profiletrust.dto.response.FaceVerificationResponse;
import com.team.dating_backend.profiletrust.entity.FaceVerificationSession;
import com.team.dating_backend.profiletrust.enums.ProfileTrustErrorCode;
import com.team.dating_backend.profiletrust.enums.VerificationDecision;
import com.team.dating_backend.profiletrust.exception.ProfileTrustBusinessException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class FaceVerificationService {

    private static final long MAX_PHOTO_BYTES = 10 * 1024 * 1024;
    private static final Set<String> PHOTO_CONTENT_TYPES = Set.of("image/jpeg", "image/png");
    private final FaceVerificationSessionStore store;
    private final FaceVerificationAiClient aiClient;
    private final VerificationVideoReader videoReader;
    private final FileUploadIntentRepository uploadIntentRepository;
    private final FileStorage fileStorage;

    public FaceVerificationResponse create(Long ownerUserId, Long uploadIntentId) {
        validateId(ownerUserId);
        validateId(uploadIntentId);
        FileUploadIntent photo = approvedPhoto(ownerUserId, uploadIntentId, null);
        ChallengeResponse challenge = aiClient.createChallenge();
        if (challenge == null || challenge.challengeToken() == null || challenge.challengeToken().isBlank()
            || challenge.challengeToken().length() > 2048
            || !List.of("LOOK_STRAIGHT").equals(challenge.challenges())
            || challenge.expiresAt() == null || !challenge.expiresAt().isAfter(OffsetDateTime.now())) {
            throw failure(ProfileTrustErrorCode.AI_SERVER_RESPONSE_INVALID);
        }
        return store.create(ownerUserId, uploadIntentId, photo.getValidatedVersionToken(), challenge);
    }

    public FaceVerificationResponse get(Long ownerUserId, Long sessionId) {
        validateId(ownerUserId);
        validateId(sessionId);
        return store.get(ownerUserId, sessionId);
    }

    public FaceVerificationResponse submit(
        Long ownerUserId, Long sessionId, int attemptNo, MultipartFile liveVideo) {
        validateId(ownerUserId);
        validateId(sessionId);
        if (attemptNo < 1) {
            throw new RequestValidationException();
        }
        VerificationVideoReader.Video video = videoReader.read(liveVideo);
        FaceVerificationSessionStore.Admission admission = store.claim(
            ownerUserId, sessionId, attemptNo, video.hash(), video.contentType());
        if (!admission.acquired()) {
            return responseOrThrow(admission.previousResult());
        }

        try {
            FaceVerificationSession session = admission.session();
            FileUploadIntent photo = approvedPhoto(
                ownerUserId, session.getUploadIntentId(), session.getPhotoVersionToken());
            byte[] primaryPhoto;
            try {
                primaryPhoto = fileStorage.readUploadedObject(photo.getStagingKey(), session.getPhotoVersionToken());
            } catch (FileStorageException exception) {
                throw storageFailure(exception);
            }
            VerificationResponse result = aiClient.verify(
                session.getChallengeToken(), primaryPhoto, photo.getDeclaredContentType(),
                video.bytes(), video.contentType());
            validateResult(result);
            return responseOrThrow(store.finish(ownerUserId, sessionId, attemptNo, result));
        } catch (ProfileTrustBusinessException exception) {
            store.fail(ownerUserId, sessionId, attemptNo, (ProfileTrustErrorCode) exception.getErrorCode());
            throw exception;
        } catch (RuntimeException exception) {
            store.fail(ownerUserId, sessionId, attemptNo, ProfileTrustErrorCode.VERIFICATION_FAILED);
            throw new ProfileTrustBusinessException(ProfileTrustErrorCode.VERIFICATION_FAILED, exception);
        }
    }

    private FileUploadIntent approvedPhoto(Long ownerUserId, Long uploadIntentId, String expectedVersion) {
        FileUploadIntent photo = uploadIntentRepository.findOwned(uploadIntentId, ownerUserId)
            .orElseThrow(() -> failure(ProfileTrustErrorCode.PRIMARY_PHOTO_NOT_FOUND));
        if (!photo.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw failure(ProfileTrustErrorCode.PRIMARY_PHOTO_EXPIRED);
        }
        String approvedVersion = photo.getValidatedVersionToken();
        if (photo.getStatus() != FileUploadIntentStatus.PENDING || approvedVersion == null) {
            throw failure(ProfileTrustErrorCode.PRIMARY_PHOTO_NOT_READY);
        }
        if (expectedVersion != null && !expectedVersion.equals(approvedVersion)) {
            throw failure(ProfileTrustErrorCode.PRIMARY_PHOTO_CHANGED);
        }
        if (!PHOTO_CONTENT_TYPES.contains(photo.getDeclaredContentType())) {
            throw failure(ProfileTrustErrorCode.PRIMARY_PHOTO_NOT_READY);
        }
        try {
            var object = fileStorage.inspectUploadedObject(photo.getStagingKey())
                .orElseThrow(() -> failure(ProfileTrustErrorCode.PRIMARY_PHOTO_NOT_READY));
            if (!approvedVersion.equals(object.versionToken())) {
                throw failure(ProfileTrustErrorCode.PRIMARY_PHOTO_CHANGED);
            }
            if (object.fileSize() <= 0 || object.fileSize() > MAX_PHOTO_BYTES
                || !photo.getDeclaredContentType().equals(object.contentType())) {
                throw failure(ProfileTrustErrorCode.PRIMARY_PHOTO_NOT_READY);
            }
            return photo;
        } catch (FileStorageException exception) {
            throw storageFailure(exception);
        }
    }

    private void validateResult(VerificationResponse result) {
        if (result == null || result.decision() == null || result.reasonCodes() == null
            || result.reasonCodes().stream().anyMatch(reason -> reason == null)
            || result.liveness() == null || result.liveness().passed() == null) {
            throw failure(ProfileTrustErrorCode.AI_SERVER_RESPONSE_INVALID);
        }
        if (result.decision() == VerificationDecision.VERIFIED) {
            if (!result.liveness().passed() || !result.reasonCodes().isEmpty()) {
                throw failure(ProfileTrustErrorCode.AI_SERVER_RESPONSE_INVALID);
            }
        } else if (result.reasonCodes().isEmpty()
            || (result.decision() == VerificationDecision.NOT_VERIFIED && !result.liveness().passed())) {
            throw failure(ProfileTrustErrorCode.AI_SERVER_RESPONSE_INVALID);
        }
    }

    private FaceVerificationResponse responseOrThrow(FaceVerificationResponse response) {
        if (response.attempt() != null && response.attempt().errorCode() != null) {
            throw failure(response.attempt().errorCode());
        }
        return response;
    }

    private ProfileTrustBusinessException storageFailure(FileStorageException exception) {
        ProfileTrustErrorCode code = exception.getErrorCode() == FileErrorCode.FILE_UPLOAD_INTENT_CONFLICT
            ? ProfileTrustErrorCode.PRIMARY_PHOTO_CHANGED
            : exception.getErrorCode() == FileErrorCode.FILE_UPLOAD_NOT_COMPLETE
                ? ProfileTrustErrorCode.PRIMARY_PHOTO_NOT_READY
                : ProfileTrustErrorCode.PRIMARY_PHOTO_UNAVAILABLE;
        return new ProfileTrustBusinessException(code, exception);
    }

    private void validateId(Long id) {
        if (id == null || id <= 0) {
            throw new RequestValidationException();
        }
    }

    private ProfileTrustBusinessException failure(ProfileTrustErrorCode code) {
        return new ProfileTrustBusinessException(code);
    }
}
