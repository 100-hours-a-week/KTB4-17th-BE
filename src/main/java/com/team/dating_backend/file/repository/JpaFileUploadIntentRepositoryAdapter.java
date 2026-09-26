package com.team.dating_backend.file.repository;

import com.team.dating_backend.file.entity.File;
import com.team.dating_backend.file.entity.FileUploadIntent;
import com.team.dating_backend.file.enums.FileErrorCode;
import com.team.dating_backend.file.enums.FileUploadIntentStatus;
import com.team.dating_backend.file.dto.FileUploadIntentClaim;
import com.team.dating_backend.file.exception.FileBusinessException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class JpaFileUploadIntentRepositoryAdapter implements FileUploadIntentRepository {

    private final FileUploadIntentJpaRepository uploadIntentJpaRepository;
    private final FileJpaRepository fileJpaRepository;

    @Override
    @Transactional
    public FileUploadIntent saveAndFlush(FileUploadIntent intent) {
        return uploadIntentJpaRepository.saveAndFlush(intent);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FileUploadIntent> findOwned(Long intentId, Long ownerUserId) {
        return uploadIntentJpaRepository.findByIdAndOwnerUserId(intentId, ownerUserId);
    }

    @Override
    @Transactional
    public FileUploadIntentClaim claimForCompletion(
        Long intentId,
        Long ownerUserId,
        LocalDateTime now) {
        FileUploadIntent intent = findForUpdate(intentId, ownerUserId);

        if (intent.getStatus() == FileUploadIntentStatus.PENDING
            && !intent.getExpiresAt().isAfter(now)) {
            intent.markExpired();
            return new FileUploadIntentClaim(uploadIntentJpaRepository.saveAndFlush(intent), false);
        }

        if (intent.getStatus() == FileUploadIntentStatus.PENDING) {
            intent.markProcessing();
            return new FileUploadIntentClaim(uploadIntentJpaRepository.saveAndFlush(intent), true);
        }

        return new FileUploadIntentClaim(intent, false);
    }

    @Override
    @Transactional
    public File complete(Long intentId, Long ownerUserId, File file) {
        FileUploadIntent intent = findForUpdate(intentId, ownerUserId);

        if (intent.getStatus() == FileUploadIntentStatus.COMPLETED) {
            return fileJpaRepository.findByIdAndOwnerUserIdAndDeletedAtIsNull(
                intent.getCompletedFileId(),
                ownerUserId)
                .orElseThrow(() -> new FileBusinessException(FileErrorCode.FILE_NOT_FOUND));
        }

        if (intent.getStatus() != FileUploadIntentStatus.PROCESSING) {
            throw new FileBusinessException(FileErrorCode.FILE_UPLOAD_INTENT_CONFLICT);
        }

        File savedFile = fileJpaRepository.saveAndFlush(file);
        intent.markCompleted(savedFile.getId());
        uploadIntentJpaRepository.saveAndFlush(intent);
        return savedFile;
    }

    @Override
    @Transactional
    public void releaseForRetry(Long intentId, Long ownerUserId) {
        FileUploadIntent intent = findForUpdate(intentId, ownerUserId);
        if (intent.getStatus() == FileUploadIntentStatus.PROCESSING) {
            intent.markPending();
            uploadIntentJpaRepository.saveAndFlush(intent);
        }
    }

    @Override
    @Transactional
    public void markFailed(Long intentId, Long ownerUserId) {
        FileUploadIntent intent = findForUpdate(intentId, ownerUserId);
        if (intent.getStatus() == FileUploadIntentStatus.PROCESSING) {
            intent.markFailed();
            uploadIntentJpaRepository.saveAndFlush(intent);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<FileUploadIntent> findCleanupCandidates(LocalDateTime now, int limit) {
        return uploadIntentJpaRepository.findByExpiresAtBeforeAndStagingCleanedAtIsNullOrderByExpiresAtAsc(
            now,
            PageRequest.of(0, limit));
    }

    @Override
    @Transactional
    public void markCleanupComplete(Long intentId, LocalDateTime cleanedAt) {
        uploadIntentJpaRepository.findById(intentId).ifPresent(intent -> {
            intent.markExpired();
            intent.markStagingCleaned(cleanedAt);
            uploadIntentJpaRepository.saveAndFlush(intent);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<FileUploadIntent> findOldCleanedIntents(LocalDateTime expiresBefore, int limit) {
        return uploadIntentJpaRepository.findByStagingCleanedAtIsNotNullAndExpiresAtBeforeOrderByExpiresAtAsc(
            expiresBefore,
            PageRequest.of(0, limit));
    }

    @Override
    @Transactional
    public void deleteIntent(Long intentId) {
        uploadIntentJpaRepository.deleteById(intentId);
    }

    private FileUploadIntent findForUpdate(Long intentId, Long ownerUserId) {
        return uploadIntentJpaRepository.findByIdAndOwnerUserIdForUpdate(intentId, ownerUserId)
            .orElseThrow(() -> new FileBusinessException(FileErrorCode.FILE_UPLOAD_INTENT_NOT_FOUND));
    }
}
