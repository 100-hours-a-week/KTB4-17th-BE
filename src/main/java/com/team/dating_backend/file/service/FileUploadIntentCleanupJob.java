package com.team.dating_backend.file.service;

import com.team.dating_backend.file.config.FileProperties;
import com.team.dating_backend.file.entity.FileUploadIntent;
import com.team.dating_backend.file.enums.FileUploadIntentStatus;
import com.team.dating_backend.file.repository.FileUploadIntentRepository;
import com.team.dating_backend.file.storage.FileStorage;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FileUploadIntentCleanupJob {

    private final FileStorage fileStorage;
    private final FileUploadIntentRepository uploadIntentRepository;
    private final FileProperties fileProperties;

    @Scheduled(fixedDelayString = "${app.file.cleanup-delay-ms}")
    public void cleanExpiredObjects() {
        LocalDateTime now = LocalDateTime.now();
        List<FileUploadIntent> intents = uploadIntentRepository.findCleanupCandidates(
            now,
            fileProperties.getCleanupBatchSize());

        for (FileUploadIntent intent : intents) {
            cleanIntent(intent, now);
        }

        LocalDateTime retentionCutoff = now.minus(fileProperties.getUploadIntentRetention());
        List<FileUploadIntent> oldIntents = uploadIntentRepository.findOldCleanedIntents(
            retentionCutoff,
            fileProperties.getCleanupBatchSize());
        for (FileUploadIntent intent : oldIntents) {
            try {
                uploadIntentRepository.deleteIntent(intent.getId());
            } catch (RuntimeException exception) {
                log.warn("보관 기간이 지난 업로드 요청 정리에 실패했습니다. intentId={}", intent.getId(), exception);
            }
        }
    }

    private void cleanIntent(FileUploadIntent intent, LocalDateTime now) {
        try {
            fileStorage.delete(intent.getStagingKey());
            if (intent.getStatus() != FileUploadIntentStatus.COMPLETED) {
                fileStorage.delete(intent.getFinalStorageKey());
            }
            uploadIntentRepository.markCleanupComplete(intent.getId(), now);
        } catch (RuntimeException exception) {
            log.warn("만료된 파일 업로드 객체 정리에 실패했습니다. intentId={}", intent.getId(), exception);
        }
    }
}
