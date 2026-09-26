package com.team.dating_backend.file.support;

import com.team.dating_backend.file.dto.FileUploadIntentClaim;
import com.team.dating_backend.file.entity.File;
import com.team.dating_backend.file.entity.FileUploadIntent;
import com.team.dating_backend.file.enums.FileUploadIntentStatus;
import com.team.dating_backend.file.enums.FileErrorCode;
import com.team.dating_backend.file.exception.FileBusinessException;
import com.team.dating_backend.file.repository.FileRepository;
import com.team.dating_backend.file.repository.FileUploadIntentRepository;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FakeFileUploadIntentRepository implements FileUploadIntentRepository {

    private final Map<Long, FileUploadIntent> intents = new HashMap<>();
    private final FileRepository fileRepository;
    private long nextId = 1L;
    private RuntimeException nextCompleteException;

    public FakeFileUploadIntentRepository(FileRepository fileRepository) {
        this.fileRepository = fileRepository;
    }

    @Override
    public FileUploadIntent saveAndFlush(FileUploadIntent intent) {
        if (intent.getId() == null) {
            assignId(intent, nextId++);
        }
        intents.put(intent.getId(), intent);
        return intent;
    }

    @Override
    public Optional<FileUploadIntent> findOwned(Long intentId, Long ownerUserId) {
        return Optional.ofNullable(intents.get(intentId))
            .filter(intent -> intent.getOwnerUserId().equals(ownerUserId));
    }

    @Override
    public FileUploadIntentClaim claimForCompletion(
        Long intentId,
        Long ownerUserId,
        LocalDateTime now) {
        FileUploadIntent intent = findForOwner(intentId, ownerUserId);
        if (intent.getStatus() == FileUploadIntentStatus.PENDING
            && !intent.getExpiresAt().isAfter(now)) {
            intent.markExpired();
            return new FileUploadIntentClaim(intent, false);
        }

        if (intent.getStatus() == FileUploadIntentStatus.PENDING) {
            intent.markProcessing();
            return new FileUploadIntentClaim(intent, true);
        }

        return new FileUploadIntentClaim(intent, false);
    }

    @Override
    public File complete(Long intentId, Long ownerUserId, File file) {
        FileUploadIntent intent = findForOwner(intentId, ownerUserId);
        if (nextCompleteException != null) {
            RuntimeException exception = nextCompleteException;
            nextCompleteException = null;
            throw exception;
        }

        File savedFile = fileRepository.saveAndFlush(file);
        intent.markCompleted(savedFile.getId());
        return savedFile;
    }

    @Override
    public void releaseForRetry(Long intentId, Long ownerUserId) {
        FileUploadIntent intent = findForOwner(intentId, ownerUserId);
        if (intent.getStatus() == FileUploadIntentStatus.PROCESSING) {
            intent.markPending();
        }
    }

    @Override
    public void markFailed(Long intentId, Long ownerUserId) {
        findForOwner(intentId, ownerUserId).markFailed();
    }

    @Override
    public List<FileUploadIntent> findCleanupCandidates(LocalDateTime now, int limit) {
        return intents.values().stream()
            .filter(intent -> intent.getStagingCleanedAt() == null)
            .filter(intent -> intent.getExpiresAt().isBefore(now))
            .sorted(Comparator.comparing(FileUploadIntent::getExpiresAt))
            .limit(limit)
            .toList();
    }

    @Override
    public void markCleanupComplete(Long intentId, LocalDateTime cleanedAt) {
        FileUploadIntent intent = intents.get(intentId);
        if (intent != null) {
            intent.markExpired();
            intent.markStagingCleaned(cleanedAt);
        }
    }

    @Override
    public List<FileUploadIntent> findOldCleanedIntents(LocalDateTime expiresBefore, int limit) {
        return intents.values().stream()
            .filter(intent -> intent.getStagingCleanedAt() != null)
            .filter(intent -> intent.getExpiresAt().isBefore(expiresBefore))
            .sorted(Comparator.comparing(FileUploadIntent::getExpiresAt))
            .limit(limit)
            .toList();
    }

    @Override
    public void deleteIntent(Long intentId) {
        intents.remove(intentId);
    }

    public void failNextComplete() {
        nextCompleteException = new RuntimeException("DB 완료 처리 실패");
    }

    public FileUploadIntent intent(Long intentId) {
        return intents.get(intentId);
    }

    private FileUploadIntent findForOwner(Long intentId, Long ownerUserId) {
        return findOwned(intentId, ownerUserId)
            .orElseThrow(() -> new FileBusinessException(FileErrorCode.FILE_UPLOAD_INTENT_NOT_FOUND));
    }

    private void assignId(FileUploadIntent intent, Long id) {
        try {
            Field idField = FileUploadIntent.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(intent, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Fake Repository가 업로드 요청 ID를 할당하지 못했습니다.", exception);
        }
    }
}
