package com.team.dating_backend.profiletrust.service;

import com.team.dating_backend.common.exception.BusinessException;
import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.file.config.FileProperties;
import com.team.dating_backend.file.dto.FileUploadIntentClaim;
import com.team.dating_backend.file.entity.FileUploadIntent;
import com.team.dating_backend.file.enums.FileErrorCode;
import com.team.dating_backend.file.enums.FileUploadIntentStatus;
import com.team.dating_backend.file.exception.FileBusinessException;
import com.team.dating_backend.file.exception.FileStorageException;
import com.team.dating_backend.file.repository.FileUploadIntentRepository;
import com.team.dating_backend.file.service.ImageSignatureValidator;
import com.team.dating_backend.file.storage.FileStorage;
import com.team.dating_backend.file.storage.StoredObjectInfo;
import com.team.dating_backend.profiletrust.client.PrimaryPhotoAiClient;
import com.team.dating_backend.profiletrust.dto.PrimaryPhotoCheckResult;
import com.team.dating_backend.profiletrust.dto.ai.PrimaryPhotoAiPayloads.FrontalCheckResponse;
import com.team.dating_backend.profiletrust.dto.ai.PrimaryPhotoAiPayloads.SyntheticCheckResponse;
import com.team.dating_backend.profiletrust.enums.ProfileTrustErrorCode;
import com.team.dating_backend.profiletrust.exception.ProfileTrustBusinessException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrimaryPhotoCheckService {

    private static final Set<String> FRONTAL_REASON_CODES = Set.of(
        "NO_FACE",
        "MULTIPLE_FACES",
        "NON_FRONTAL_FACE",
        "IMAGE_TOO_SMALL",
        "IMAGE_TOO_BLURRY",
        "IMAGE_TOO_DARK",
        "IMAGE_TOO_BRIGHT");

    private final FileStorage fileStorage;
    private final FileUploadIntentRepository uploadIntentRepository;
    private final FileProperties fileProperties;
    private final ImageSignatureValidator imageSignatureValidator;
    private final PrimaryPhotoAiClient primaryPhotoAiClient;

    public PrimaryPhotoCheckResult check(Long ownerUserId, Long uploadIntentId) {
        validateIds(ownerUserId, uploadIntentId);
        FileUploadIntentClaim claim = uploadIntentRepository.claimForProcessing(
            uploadIntentId, ownerUserId, LocalDateTime.now());
        FileUploadIntent intent = claim.intent();
        validateClaim(claim);

        try {
            StoredObjectInfo object = inspect(intent);
            validateImage(intent, object);

            if (Objects.equals(intent.getValidatedVersionToken(), object.versionToken())) {
                uploadIntentRepository.completeValidation(
                    intent.getId(), ownerUserId, object.versionToken());
                return passed();
            }

            byte[] image = read(intent, object);
            List<String> reasonCodes = inspectWithAi(image, object.contentType());
            if (!reasonCodes.isEmpty()) {
                return reject(intent, reasonCodes);
            }

            uploadIntentRepository.completeValidation(
                intent.getId(), ownerUserId, object.versionToken());
            return passed();
        } catch (BusinessException exception) {
            releaseForRetry(intent, exception);
            throw exception;
        } catch (RuntimeException exception) {
            releaseForRetry(intent, exception);
            throw new ProfileTrustBusinessException(
                ProfileTrustErrorCode.AI_SERVER_UNAVAILABLE, exception);
        }
    }

    private List<String> inspectWithAi(byte[] image, String contentType) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var frontalFuture = CompletableFuture.supplyAsync(
                () -> primaryPhotoAiClient.checkFrontal(image, contentType), executor);
            var syntheticFuture = CompletableFuture.supplyAsync(
                () -> primaryPhotoAiClient.checkSynthetic(image, contentType), executor);
            CompletableFuture.allOf(frontalFuture, syntheticFuture).join();

            FrontalCheckResponse frontal = frontalFuture.join();
            SyntheticCheckResponse synthetic = syntheticFuture.join();
            validateFrontalResponse(frontal);
            validateSyntheticResponse(synthetic);

            List<String> reasonCodes = new ArrayList<>(frontal.reasonCodes());
            if (!"CLEAR".equals(synthetic.decision())) {
                reasonCodes.add(synthetic.decision());
            }
            return reasonCodes;
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new ProfileTrustBusinessException(
                ProfileTrustErrorCode.AI_SERVER_UNAVAILABLE, exception.getCause());
        }
    }

    private void validateClaim(FileUploadIntentClaim claim) {
        FileUploadIntent intent = claim.intent();
        if (intent.getStatus() == FileUploadIntentStatus.EXPIRED) {
            throw new FileBusinessException(FileErrorCode.FILE_UPLOAD_INTENT_EXPIRED);
        }
        if (intent.getStatus() == FileUploadIntentStatus.FAILED
            || intent.getStatus() == FileUploadIntentStatus.COMPLETED
            || !claim.acquired()) {
            throw new FileBusinessException(FileErrorCode.FILE_UPLOAD_INTENT_CONFLICT);
        }
    }

    private StoredObjectInfo inspect(FileUploadIntent intent) {
        try {
            return fileStorage.inspectUploadedObject(intent.getStagingKey())
                .orElseThrow(() -> new FileBusinessException(
                    FileErrorCode.FILE_UPLOAD_NOT_COMPLETE));
        } catch (FileStorageException exception) {
            throw new FileBusinessException(exception.getErrorCode(), exception);
        }
    }

    private void validateImage(FileUploadIntent intent, StoredObjectInfo object) {
        if (object.fileSize() <= 0) {
            rejectInvalidImage(intent, FileErrorCode.FILE_INVALID_CONTENT);
        }
        if (object.fileSize() > fileProperties.getMaxSizeBytes()) {
            rejectInvalidImage(intent, FileErrorCode.FILE_TOO_LARGE);
        }
        String contentType = object.contentType() == null
            ? ""
            : object.contentType().strip().toLowerCase(Locale.ROOT);
        if (!intent.getDeclaredContentType().equals(contentType)) {
            rejectInvalidImage(intent, FileErrorCode.FILE_TYPE_NOT_ALLOWED);
        }
        if (!imageSignatureValidator.isValid(contentType, object.signatureBytes())) {
            rejectInvalidImage(intent, FileErrorCode.FILE_INVALID_CONTENT);
        }
        if (object.versionToken() == null || object.versionToken().isBlank()) {
            rejectInvalidImage(intent, FileErrorCode.FILE_INVALID_CONTENT);
        }
    }

    private byte[] read(FileUploadIntent intent, StoredObjectInfo object) {
        try {
            return fileStorage.readUploadedObject(intent.getStagingKey(), object.versionToken());
        } catch (FileStorageException exception) {
            throw new FileBusinessException(exception.getErrorCode(), exception);
        }
    }

    private void validateFrontalResponse(FrontalCheckResponse response) {
        if (response == null || response.reasonCodes() == null) {
            throw invalidAiResponse();
        }
        if (!"PASS".equals(response.decision()) && !"RETRY".equals(response.decision())) {
            throw invalidAiResponse();
        }
        if (response.reasonCodes().stream()
            .anyMatch(code -> code == null || !FRONTAL_REASON_CODES.contains(code))) {
            throw invalidAiResponse();
        }
        if ("RETRY".equals(response.decision()) && response.reasonCodes().isEmpty()) {
            throw invalidAiResponse();
        }
        if ("PASS".equals(response.decision()) && !response.reasonCodes().isEmpty()) {
            throw invalidAiResponse();
        }
    }

    private void validateSyntheticResponse(SyntheticCheckResponse response) {
        if (response == null || (!"CLEAR".equals(response.decision())
            && !"SYNTHETIC_RISK".equals(response.decision())
            && !"CONFIRMED_SYNTHETIC".equals(response.decision()))) {
            throw invalidAiResponse();
        }
    }

    private PrimaryPhotoCheckResult reject(
        FileUploadIntent intent,
        List<String> reasonCodes) {
        try {
            uploadIntentRepository.markFailed(intent.getId(), intent.getOwnerUserId());
        } catch (RuntimeException exception) {
            throw new FileBusinessException(FileErrorCode.FILE_UPLOAD_FAILED, exception);
        }
        cleanupRejectedPhoto(intent);
        return new PrimaryPhotoCheckResult(false, List.copyOf(reasonCodes));
    }

    private void rejectInvalidImage(FileUploadIntent intent, FileErrorCode errorCode) {
        try {
            uploadIntentRepository.markFailed(intent.getId(), intent.getOwnerUserId());
        } catch (RuntimeException exception) {
            throw new FileBusinessException(FileErrorCode.FILE_UPLOAD_FAILED, exception);
        }
        cleanupRejectedPhoto(intent);
        throw new FileBusinessException(errorCode);
    }

    private void cleanupRejectedPhoto(FileUploadIntent intent) {
        try {
            fileStorage.delete(intent.getStagingKey());
        } catch (RuntimeException exception) {
            log.warn("거부된 대표사진 임시 파일 정리에 실패했습니다. intentId={}",
                intent.getId(), exception);
        }
        try {
            fileStorage.delete(intent.getFinalStorageKey());
        } catch (RuntimeException exception) {
            log.warn("거부된 대표사진 최종 파일 정리에 실패했습니다. intentId={}",
                intent.getId(), exception);
        }
    }

    private void releaseForRetry(FileUploadIntent intent, RuntimeException originalException) {
        try {
            uploadIntentRepository.releaseForRetry(intent.getId(), intent.getOwnerUserId());
        } catch (RuntimeException releaseException) {
            originalException.addSuppressed(releaseException);
            log.error("대표사진 검사 요청을 재시도 상태로 되돌리지 못했습니다. intentId={}",
                intent.getId(), releaseException);
        }
    }

    private void validateIds(Long ownerUserId, Long uploadIntentId) {
        if (ownerUserId == null || ownerUserId <= 0 || uploadIntentId == null || uploadIntentId <= 0) {
            throw new RequestValidationException();
        }
    }

    private PrimaryPhotoCheckResult passed() {
        return new PrimaryPhotoCheckResult(true, List.of());
    }

    private ProfileTrustBusinessException invalidAiResponse() {
        return new ProfileTrustBusinessException(ProfileTrustErrorCode.AI_SERVER_RESPONSE_INVALID);
    }
}
