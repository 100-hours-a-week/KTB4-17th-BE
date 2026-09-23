package com.team.dating_backend.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.file.dto.FileReadResult;
import com.team.dating_backend.file.dto.FileUploadCommand;
import com.team.dating_backend.file.dto.FileUploadResult;
import com.team.dating_backend.file.dto.UploadFile;
import com.team.dating_backend.file.entity.File;
import com.team.dating_backend.file.enums.FileErrorCode;
import com.team.dating_backend.file.exception.FileDeleteException;
import com.team.dating_backend.file.exception.FileNotFoundException;
import com.team.dating_backend.file.exception.FileReadException;
import com.team.dating_backend.file.exception.FileStorageException;
import com.team.dating_backend.file.exception.FileUploadException;
import com.team.dating_backend.file.support.FailingFileRepository;
import com.team.dating_backend.file.support.FailingFileStorage;
import com.team.dating_backend.file.support.FakeFileRepository;
import com.team.dating_backend.file.support.FakeFileStorage;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FileServiceTest {

    @Test
    void 파일_업로드에_성공한다() {
        // Given
        FakeFileStorage storage = new FakeFileStorage();
        FakeFileRepository repository = new FakeFileRepository();

        FileService fileService = new FileService(storage, repository);

        byte[] content = "image-content".getBytes(StandardCharsets.UTF_8);

        UploadFile uploadFile = new UploadFile("profile.jpg", "image/jpeg", content);

        FileUploadCommand command = new FileUploadCommand(1L, uploadFile);

        // When
        FileUploadResult result = fileService.upload(command);

        // Then
        assertThat(result.fileId()).isNotNull();

        assertThat(storage.wasSaved()).isTrue();

        assertThat(storage.savedContent()).containsExactly(content);

        assertThat(storage.savedMimeType()).isEqualTo("image/jpeg");

        assertThat(repository.savedFile()).isNotNull();

        assertThat(repository.savedFile().getOwnerUserId()).isEqualTo(1L);

        assertThat(repository.savedFile().getOriginalName()).isEqualTo("profile.jpg");

        assertThat(repository.savedFile().getMimeType()).isEqualTo("image/jpeg");

        assertThat(repository.savedFile().getFileSize()).isEqualTo(content.length);

        assertThat(repository.savedFile().getStorageKey()).isEqualTo(storage.savedStorageKey());
    }

    @Test
    void 빈_파일은_업로드하지_않는다() {
        // Given
        FakeFileStorage storage = new FakeFileStorage();
        FakeFileRepository repository = new FakeFileRepository();

        FileService fileService = new FileService(storage, repository);

        UploadFile uploadFile = new UploadFile("empty.jpg", "image/jpeg", new byte[0]);

        FileUploadCommand command = new FileUploadCommand(1L, uploadFile);

        // When & Then
        RequestValidationException exception =
                assertThrows(RequestValidationException.class, () -> fileService.upload(command));

        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_REQUEST);

        assertThat(storage.wasSaved()).isFalse();
        assertThat(repository.savedFile()).isNull();
    }

    @Test
    void 파일_저장소_저장에_실패하면_DB에_저장하지_않는다() {
        // Given
        FakeFileRepository repository = new FakeFileRepository();
        FailingFileStorage storage = new FailingFileStorage();

        FileService fileService = new FileService(storage, repository);

        UploadFile uploadFile =
                new UploadFile(
                        "profile.jpg",
                        "image/jpeg",
                        "image-content".getBytes(StandardCharsets.UTF_8));

        FileUploadCommand command = new FileUploadCommand(1L, uploadFile);

        // When
        FileUploadException exception =
                assertThrows(FileUploadException.class, () -> fileService.upload(command));

        // Then
        assertThat(exception.getErrorCode()).isEqualTo(FileErrorCode.FILE_UPLOAD_FAILED);
        assertThat(exception.getCause()).isInstanceOf(FileStorageException.class);
        assertThat(storage.wasUploadAttempted()).isTrue();
        assertThat(repository.savedFile()).isNull();
    }

    @Test
    void DB_저장_실패하면_Storage를_정리한다() {
        FailingFileRepository repository = new FailingFileRepository();
        FakeFileStorage storage = new FakeFileStorage();

        FileService fileService = new FileService(storage, repository);

        UploadFile uploadFile =
                new UploadFile(
                        "profile.jpg",
                        "image/jpeg",
                        "image-content".getBytes(StandardCharsets.UTF_8));

        FileUploadCommand command = new FileUploadCommand(1L, uploadFile);

        FileUploadException exception =
                assertThrows(FileUploadException.class, () -> fileService.upload(command));

        assertThat(exception.getErrorCode()).isEqualTo(FileErrorCode.FILE_UPLOAD_FAILED);
        assertThat(exception.getCause()).isInstanceOf(RuntimeException.class);
        assertThat(storage.wasDeleted()).isTrue();

        assertThat(storage.deletedStorageKey()).isEqualTo(storage.savedStorageKey());
    }

    @Test
    void 활성_파일을_조회하면_메타데이터와_조회_URL을_반환한다() {
        // Given
        FakeFileStorage storage = new FakeFileStorage();
        FakeFileRepository repository = new FakeFileRepository();
        FileService fileService = new FileService(storage, repository);

        File file = File.create(1L, "file/profile-key", "profile.jpg", "image/jpeg", 13L);
        repository.save(file);

        // When
        FileReadResult result = fileService.read(file.getId());

        // Then
        assertThat(result.fileId()).isEqualTo(file.getId());
        assertThat(result.originalName()).isEqualTo("profile.jpg");
        assertThat(result.mimeType()).isEqualTo("image/jpeg");
        assertThat(result.fileSize()).isEqualTo(13L);
        assertThat(result.readUrl()).isEqualTo("https://example.com/presigned-file-url");

        assertThat(storage.wasReadUrlCreated()).isTrue();
        assertThat(storage.readUrlStorageKey()).isEqualTo("file/profile-key");
    }

    @Test
    void 존재하지_않는_파일은_조회하지_않는다() {
        // Given
        FakeFileStorage storage = new FakeFileStorage();
        FakeFileRepository repository = new FakeFileRepository();
        FileService fileService = new FileService(storage, repository);

        // When & Then
        FileNotFoundException exception =
                assertThrows(FileNotFoundException.class, () -> fileService.read(999L));

        assertThat(exception.getErrorCode()).isEqualTo(FileErrorCode.FILE_NOT_FOUND);

        assertThat(storage.wasReadUrlCreated()).isFalse();
    }

    @Test
    void 파일을_논리삭제한다() {
        // Given
        FakeFileStorage storage = new FakeFileStorage();
        FakeFileRepository repository = new FakeFileRepository();
        FileService fileService = new FileService(storage, repository);

        File file = File.create(1L, "file/profile-key", "profile.jpg", "image/jpeg", 13L);
        repository.save(file);

        // When
        fileService.softDelete(file.getId());

        // Then
        assertThat(repository.savedFile().isDeleted()).isTrue();
        assertThat(repository.saveCount()).isEqualTo(2);
        assertThat(storage.wasDeleted()).isFalse();

        assertThatThrownBy(() -> fileService.read(file.getId()))
                .isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void 파일_조회_URL_생성에_실패하면_파일_조회_예외를_던진다() {
        // Given
        FailingFileStorage storage = new FailingFileStorage();
        FakeFileRepository repository = new FakeFileRepository();
        FileService fileService = new FileService(storage, repository);

        File file = File.create(1L, "file/profile-key", "profile.jpg", "image/jpeg", 13L);
        repository.save(file);

        // When
        FileReadException exception =
                assertThrows(FileReadException.class, () -> fileService.read(file.getId()));

        // Then
        assertThat(exception.getErrorCode()).isEqualTo(FileErrorCode.FILE_READ_URL_FAILED);
        assertThat(exception.getCause()).isInstanceOf(FileStorageException.class);
    }

    @Test
    void 파일_논리삭제_DB_저장에_실패하면_파일_삭제_예외를_던진다() {
        // Given
        FakeFileStorage storage = new FakeFileStorage();
        FakeFileRepository repository = new FakeFileRepository();
        FileService fileService = new FileService(storage, repository);

        File file = File.create(1L, "file/profile-key", "profile.jpg", "image/jpeg", 13L);
        repository.save(file);
        repository.failNextSave();

        // When
        FileDeleteException exception =
                assertThrows(FileDeleteException.class, () -> fileService.softDelete(file.getId()));

        // Then
        assertThat(exception.getErrorCode()).isEqualTo(FileErrorCode.FILE_DELETE_FAILED);
        assertThat(exception.getCause()).isInstanceOf(RuntimeException.class);
        assertThat(storage.wasDeleted()).isFalse();
    }
}
