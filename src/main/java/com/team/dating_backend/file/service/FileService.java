package com.team.dating_backend.file.service;

import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.file.config.FileProperties;
import com.team.dating_backend.file.config.S3Properties;
import com.team.dating_backend.file.dto.CreateFileUploadIntentCommand;
import com.team.dating_backend.file.dto.FileAccessUrlResult;
import com.team.dating_backend.file.dto.FileMetadataResult;
import com.team.dating_backend.file.dto.FileUploadIntentClaim;
import com.team.dating_backend.file.dto.FileUploadIntentResult;
import com.team.dating_backend.file.entity.File;
import com.team.dating_backend.file.entity.FileUploadIntent;
import com.team.dating_backend.file.enums.FileErrorCode;
import com.team.dating_backend.file.enums.FileUploadIntentStatus;
import com.team.dating_backend.file.exception.FileBusinessException;
import com.team.dating_backend.file.exception.FileStorageException;
import com.team.dating_backend.file.repository.FileRepository;
import com.team.dating_backend.file.repository.FileUploadIntentRepository;
import com.team.dating_backend.file.storage.FileStorage;
import com.team.dating_backend.file.storage.PresignedReadUrl;
import com.team.dating_backend.file.storage.PresignedUploadUrl;
import com.team.dating_backend.file.storage.StoredObjectInfo;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private static final String UPLOAD_METHOD = "PUT";
    private static final String STAGING_KEY_PREFIX = "staging/";
    private static final String FINAL_KEY_PREFIX = "files/";

    private final FileStorage fileStorage;
    private final FileRepository fileRepository;
    private final FileUploadIntentRepository uploadIntentRepository;
    private final FileProperties fileProperties;
    private final S3Properties s3Properties;
    private final ImageSignatureValidator imageSignatureValidator;

    public FileUploadIntentResult createUploadIntent(
        Long ownerUserId,
        CreateFileUploadIntentCommand command) {
        validateOwner(ownerUserId);
        validateCreateCommand(command);

        String normalizedMimeType = command.contentType().strip().toLowerCase(Locale.ROOT);
        if (!fileProperties.getAllowedMimeTypes().contains(normalizedMimeType)) {
            throw new FileBusinessException(FileErrorCode.FILE_TYPE_NOT_ALLOWED);
        }

        String originalName = command.originalName().strip();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime intentExpiresAt = now.plus(fileProperties.getUploadIntentTtl());
        String objectId = UUID.randomUUID().toString();
        String stagingKey = STAGING_KEY_PREFIX + objectId;
        String finalStorageKey = FINAL_KEY_PREFIX + objectId;

        PresignedUploadUrl uploadUrl;
        try {
            uploadUrl = fileStorage.createUploadUrl(
                stagingKey,
                normalizedMimeType,
                s3Properties.getUploadUrlExpiration());
        } catch (FileStorageException exception) {
            throw new FileBusinessException(exception.getErrorCode(), exception);
        }

        FileUploadIntent intent = FileUploadIntent.create(
            ownerUserId,
            stagingKey,
            finalStorageKey,
            originalName,
            normalizedMimeType,
            intentExpiresAt);

        FileUploadIntent savedIntent;
        try {
            savedIntent = uploadIntentRepository.saveAndFlush(intent);
        } catch (RuntimeException exception) {
            throw new FileBusinessException(FileErrorCode.FILE_UPLOAD_FAILED, exception);
        }

        Instant intentExpiresAtInstant = intentExpiresAt.atZone(ZoneId.systemDefault()).toInstant();
        return new FileUploadIntentResult(
            savedIntent.getId(),
            uploadUrl.url(),
            UPLOAD_METHOD,
            Map.of("Content-Type", normalizedMimeType),
            uploadUrl.expiresAt(),
            intentExpiresAtInstant,
            fileProperties.getMaxSizeBytes());
    }

    public FileMetadataResult completeUpload(Long ownerUserId, Long uploadIntentId) {
        validateOwner(ownerUserId);
        validateId(uploadIntentId);

        FileUploadIntentClaim claim = uploadIntentRepository.claimForCompletion(
            uploadIntentId,
            ownerUserId,
            LocalDateTime.now());
        FileUploadIntent intent = claim.intent();

        if (intent.getStatus() == FileUploadIntentStatus.COMPLETED) {
            Long completedFileId = intent.getCompletedFileId();
            File existingFile = fileRepository.findActiveByIdAndOwner(completedFileId, ownerUserId)
                .orElseThrow(() -> new FileBusinessException(FileErrorCode.FILE_NOT_FOUND));
            return toMetadata(existingFile);
        }

        if (intent.getStatus() == FileUploadIntentStatus.EXPIRED) {
            throw new FileBusinessException(FileErrorCode.FILE_UPLOAD_INTENT_EXPIRED);
        }

        if (!claim.acquired() && intent.getStatus() == FileUploadIntentStatus.PROCESSING) {
            throw new FileBusinessException(FileErrorCode.FILE_UPLOAD_INTENT_CONFLICT);
        }

        if (intent.getStatus() == FileUploadIntentStatus.FAILED) {
            throw new FileBusinessException(FileErrorCode.FILE_UPLOAD_INTENT_CONFLICT);
        }

        if (!claim.acquired()) {
            throw new FileBusinessException(FileErrorCode.FILE_UPLOAD_INTENT_CONFLICT);
        }

        Optional<StoredObjectInfo> uploadedObjectResult;
        try {
            uploadedObjectResult = fileStorage.inspectUploadedObject(intent.getStagingKey());
        } catch (FileStorageException exception) {
            FileBusinessException uploadException = new FileBusinessException(
                exception.getErrorCode(),
                exception);
            releaseForRetry(intent, uploadException);
            throw uploadException;
        }

        if (uploadedObjectResult.isEmpty()) {
            FileBusinessException exception = new FileBusinessException(
                FileErrorCode.FILE_UPLOAD_NOT_COMPLETE);
            releaseForRetry(intent, exception);
            throw exception;
        }

        StoredObjectInfo uploadedObject = uploadedObjectResult.get();

        if (uploadedObject.fileSize() <= 0) {
            rejectAndCleanup(intent, FileErrorCode.FILE_INVALID_CONTENT);
        }

        if (uploadedObject.fileSize() > fileProperties.getMaxSizeBytes()) {
            rejectAndCleanup(intent, FileErrorCode.FILE_TOO_LARGE);
        }

        String storedMimeType = uploadedObject.contentType() == null
            ? ""
            : uploadedObject.contentType().strip().toLowerCase(Locale.ROOT);
        if (!intent.getDeclaredContentType().equals(storedMimeType)) {
            rejectAndCleanup(intent, FileErrorCode.FILE_TYPE_NOT_ALLOWED);
        }

        if (!imageSignatureValidator.isValid(storedMimeType, uploadedObject.signatureBytes())) {
            rejectAndCleanup(intent, FileErrorCode.FILE_INVALID_CONTENT);
        }

        try {
            fileStorage.promote(
                intent.getStagingKey(),
                intent.getFinalStorageKey(),
                storedMimeType,
                uploadedObject.versionToken());
        } catch (FileStorageException exception) {
            FileBusinessException uploadException = new FileBusinessException(
                exception.getErrorCode(),
                exception);
            releaseForRetry(intent, uploadException);
            throw uploadException;
        }

        File file = File.create(
            ownerUserId,
            intent.getFinalStorageKey(),
            intent.getOriginalName(),
            storedMimeType,
            uploadedObject.fileSize());

        File savedFile;
        try {
            savedFile = uploadIntentRepository.complete(uploadIntentId, ownerUserId, file);
        } catch (RuntimeException exception) {
            cleanupStorage(intent.getFinalStorageKey(), exception);
            FileBusinessException uploadException = new FileBusinessException(
                FileErrorCode.FILE_UPLOAD_FAILED,
                exception);
            releaseForRetry(intent, uploadException);
            throw uploadException;
        }

        cleanupCompletedStaging(intent);
        return toMetadata(savedFile);
    }

    public FileMetadataResult getMetadata(Long ownerUserId, Long fileId) {
        File file = findOwnedActiveFile(ownerUserId, fileId);
        return toMetadata(file);
    }

    public FileAccessUrlResult createAccessUrl(Long ownerUserId, Long fileId, String disposition) {
        File file = findOwnedActiveFile(ownerUserId, fileId);
        String normalizedDisposition = normalizeDisposition(disposition);

        PresignedReadUrl readUrl;
        try {
            readUrl = fileStorage.createReadUrl(
                file.getStorageKey(),
                file.getMimeType(),
                normalizedDisposition,
                file.getOriginalName(),
                s3Properties.getDownloadUrlExpiration());
        } catch (FileStorageException exception) {
            throw new FileBusinessException(exception.getErrorCode(), exception);
        }

        return new FileAccessUrlResult(
            file.getId(),
            readUrl.url(),
            normalizedDisposition,
            readUrl.expiresAt());
    }

    public void softDelete(Long ownerUserId, Long fileId) {
        File file = findOwnedActiveFile(ownerUserId, fileId);
        try {
            file.markDeleted(LocalDateTime.now());
            fileRepository.saveAndFlush(file);
        } catch (RuntimeException exception) {
            throw new FileBusinessException(FileErrorCode.FILE_DELETE_FAILED, exception);
        }
    }

    private void validateCreateCommand(CreateFileUploadIntentCommand command) {
        if (command == null) {
            throw new RequestValidationException();
        }

        if (command.originalName() == null
            || command.originalName().isBlank()
            || command.originalName().strip().length() > 255
            || command.originalName().chars().anyMatch(Character::isISOControl)) {
            throw new RequestValidationException();
        }

        if (command.contentType() == null || command.contentType().isBlank()) {
            throw new RequestValidationException();
        }
    }

    private void validateOwner(Long ownerUserId) {
        if (ownerUserId == null || ownerUserId <= 0) {
            throw new RequestValidationException();
        }
    }

    private void validateId(Long id) {
        if (id == null || id <= 0) {
            throw new RequestValidationException();
        }
    }

    private File findOwnedActiveFile(Long ownerUserId, Long fileId) {
        validateOwner(ownerUserId);
        validateId(fileId);
        return fileRepository.findActiveByIdAndOwner(fileId, ownerUserId)
            .orElseThrow(() -> new FileBusinessException(FileErrorCode.FILE_NOT_FOUND));
    }

    private String normalizeDisposition(String disposition) {
        if (disposition == null || disposition.isBlank()) {
            return "attachment";
        }

        String normalized = disposition.strip().toLowerCase(Locale.ROOT);
        if (!normalized.equals("inline") && !normalized.equals("attachment")) {
            throw new RequestValidationException();
        }

        return normalized;
    }

    private FileMetadataResult toMetadata(File file) {
        return new FileMetadataResult(
            file.getId(),
            file.getOriginalName(),
            file.getMimeType(),
            file.getFileSize(),
            file.getCreatedAt());
    }

    private void rejectAndCleanup(
        FileUploadIntent intent,
        FileErrorCode errorCode) {
        FileBusinessException exception = new FileBusinessException(errorCode);
        try {
            uploadIntentRepository.markFailed(intent.getId(), intent.getOwnerUserId());
        } catch (RuntimeException statusException) {
            exception.addSuppressed(statusException);
        }

        cleanupStorage(intent.getStagingKey(), exception);
        cleanupStorage(intent.getFinalStorageKey(), exception);
        throw exception;
    }

    private void cleanupCompletedStaging(FileUploadIntent intent) {
        try {
            fileStorage.delete(intent.getStagingKey());
            // The presigned PUT may recreate this object until the intent URL expires.
        } catch (RuntimeException exception) {
            log.warn(
                "완료된 업로드의 임시 객체 정리에 실패했습니다. intentId={}",
                intent.getId(),
                exception);
        }
    }

    private void cleanupStorage(String storageKey, RuntimeException originalException) {
        try {
            fileStorage.delete(storageKey);
        } catch (RuntimeException cleanupException) {
            originalException.addSuppressed(cleanupException);
            log.error("S3 객체 정리에 실패했습니다. storageKey={}", storageKey, cleanupException);
        }
    }

    private void releaseForRetry(FileUploadIntent intent, RuntimeException originalException) {
        try {
            uploadIntentRepository.releaseForRetry(intent.getId(), intent.getOwnerUserId());
        } catch (RuntimeException releaseException) {
            originalException.addSuppressed(releaseException);
            log.error("업로드 요청을 재시도 가능 상태로 되돌리지 못했습니다. intentId={}", intent.getId(), releaseException);
        }
    }
}
