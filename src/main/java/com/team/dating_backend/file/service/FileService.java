package com.team.dating_backend.file.service;

import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.file.dto.FileReadResult;
import com.team.dating_backend.file.dto.FileUploadCommand;
import com.team.dating_backend.file.dto.FileUploadResult;
import com.team.dating_backend.file.dto.UploadFile;
import com.team.dating_backend.file.entity.File;
import com.team.dating_backend.file.exception.FileDeleteException;
import com.team.dating_backend.file.exception.FileNotFoundException;
import com.team.dating_backend.file.exception.FileReadException;
import com.team.dating_backend.file.exception.FileStorageException;
import com.team.dating_backend.file.exception.FileUploadException;
import com.team.dating_backend.file.repository.FileRepository;
import com.team.dating_backend.file.storage.FileStorage;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private final FileStorage fileStorage;
    private final FileRepository fileRepository;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public FileUploadResult upload(FileUploadCommand command) {
        validateUpload(command);

        UploadFile uploadFile = command.file();
        String storageKey = "file/" + UUID.randomUUID();

        File file =
                File.create(
                        command.ownerUserId(),
                        storageKey,
                        uploadFile.originalName(),
                        uploadFile.mimeType(),
                        uploadFile.content().length);

        try {
            fileStorage.upload(storageKey, uploadFile.content(), uploadFile.mimeType());
        } catch (FileStorageException exception) {
            throw new FileUploadException("파일 저장소 업로드에 실패했습니다.", exception);
        }

        File savedFile;
        try {
            savedFile = fileRepository.save(file);
        } catch (RuntimeException exception) {
            cleanupStorage(storageKey, exception);

            throw new FileUploadException("파일 정보 저장에 실패했습니다.", exception);
        }

        return new FileUploadResult(savedFile.getId());
    }

    @Transactional(readOnly = true)
    public FileReadResult read(Long fileId) {
        File file = findActiveFile(fileId);

        String readUrl;
        try {
            readUrl = fileStorage.createReadUrl(file.getStorageKey());
        } catch (FileStorageException exception) {
            throw new FileReadException("파일 조회 URL 생성에 실패했습니다.", exception);
        }

        return new FileReadResult(
                file.getId(),
                file.getOriginalName(),
                file.getMimeType(),
                file.getFileSize(),
                readUrl);
    }

    @Transactional
    public void softDelete(Long fileId) {
        File file = findActiveFile(fileId);

        try {
            file.markDeleted(LocalDateTime.now());
            fileRepository.save(file);
        } catch (RuntimeException exception) {
            throw new FileDeleteException("파일 논리삭제에 실패했습니다. fileId=" + fileId, exception);
        }
    }

    private File findActiveFile(Long fileId) {
        if (fileId == null || fileId <= 0) {
            throw new RequestValidationException("유효한 파일 ID가 필요합니다.");
        }

        return fileRepository
                .findActiveById(fileId)
                .orElseThrow(() -> new FileNotFoundException(fileId));
    }

    private void validateUpload(FileUploadCommand command) {
        if (command == null || command.file() == null) {
            throw new RequestValidationException("업로드할 파일이 필요합니다.");
        }

        if (command.ownerUserId() == null || command.ownerUserId() <= 0) {
            throw new RequestValidationException("유효한 파일 소유자 ID가 필요합니다.");
        }

        UploadFile file = command.file();

        if (file.content() == null || file.content().length == 0) {
            throw new RequestValidationException("빈 파일은 업로드할 수 없습니다.");
        }

        if (file.originalName() == null
                || file.originalName().isBlank()
                || file.originalName().length() > 255) {
            throw new RequestValidationException("파일명은 1~255자여야 합니다.");
        }

        if (file.mimeType() == null
                || file.mimeType().isBlank()
                || file.mimeType().length() > 100) {
            throw new RequestValidationException("MIME 타입은 1~100자여야 합니다.");
        }
    }

    private void cleanupStorage(String storageKey, RuntimeException originalException) {
        try {
            fileStorage.delete(storageKey);
        } catch (RuntimeException cleanupException) {
            originalException.addSuppressed(cleanupException);

            log.error("업로드 실패 후 파일 정리에 실패했습니다. storageKey={}", storageKey, cleanupException);
        }
    }
}
