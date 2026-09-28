package com.team.dating_backend.file.repository;

import com.team.dating_backend.file.entity.File;
import com.team.dating_backend.file.entity.FileUploadIntent;
import com.team.dating_backend.file.dto.FileUploadIntentClaim;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FileUploadIntentRepository {

    FileUploadIntent saveAndFlush(FileUploadIntent intent);

    Optional<FileUploadIntent> findOwned(Long intentId, Long ownerUserId);

    FileUploadIntentClaim claimForCompletion(Long intentId, Long ownerUserId, LocalDateTime now);

    File complete(Long intentId, Long ownerUserId, File file);

    void releaseForRetry(Long intentId, Long ownerUserId);

    void markFailed(Long intentId, Long ownerUserId);

    List<FileUploadIntent> findCleanupCandidates(LocalDateTime now, int limit);

    void markCleanupComplete(Long intentId, LocalDateTime cleanedAt);

    List<FileUploadIntent> findOldCleanedIntents(LocalDateTime expiresBefore, int limit);

    void deleteIntent(Long intentId);
}
