package com.team.dating_backend.file.service;

import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.file.config.S3Properties;
import com.team.dating_backend.file.dto.FileAccessUrlResult;
import com.team.dating_backend.file.entity.File;
import com.team.dating_backend.file.exception.FileBusinessException;
import com.team.dating_backend.file.exception.FileStorageException;
import com.team.dating_backend.file.storage.FileStorage;
import com.team.dating_backend.file.storage.PresignedReadUrl;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FileAccessUrlCreateService {

    private final FileStorage fileStorage;
    private final S3Properties s3Properties;

    public FileAccessUrlResult createPresignedAccessUrl(File file, String disposition) {
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
}
