package com.team.dating_backend.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.team.dating_backend.file.config.FileProperties;
import com.team.dating_backend.file.config.S3Properties;
import com.team.dating_backend.file.dto.CreateFileUploadIntentCommand;
import com.team.dating_backend.file.dto.FileAccessUrlResult;
import com.team.dating_backend.file.dto.FileMetadataResult;
import com.team.dating_backend.file.dto.FileUploadIntentResult;
import com.team.dating_backend.file.entity.File;
import com.team.dating_backend.file.enums.FileErrorCode;
import com.team.dating_backend.file.enums.FileUploadIntentStatus;
import com.team.dating_backend.file.exception.FileBusinessException;
import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.file.support.FakeFileRepository;
import com.team.dating_backend.file.support.FakeFileStorage;
import com.team.dating_backend.file.support.FakeFileUploadIntentRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class FileServiceTest {

    private static final Long USER_ID = 41L;
    private static final String PNG_MIME_TYPE = "image/png";

    @Test
    void 업로드_URL과_요청_정책을_반환한다() {
        TestContext context = newContext(10_000);

        FileUploadIntentResult result = context.fileService().createUploadIntent(
            USER_ID,
            new CreateFileUploadIntentCommand("sample.png", PNG_MIME_TYPE));

        assertThat(result.uploadIntentId()).isNotNull();
        assertThat(result.uploadUrl()).isEqualTo("https://example.com/presigned-put-url");
        assertThat(result.method()).isEqualTo("PUT");
        assertThat(result.headers()).containsEntry("Content-Type", PNG_MIME_TYPE);
        assertThat(result.maxFileSizeBytes()).isEqualTo(10_000);
        assertThat(context.storage().lastUploadKey()).startsWith("staging/");
        assertThat(context.intentRepository().intent(result.uploadIntentId()).getFinalStorageKey())
            .startsWith("files/");
    }

    @Test
    void S3_직접업로드_완료후_검증하고_최종파일과_메타데이터를_저장한다() {
        TestContext context = newContext(10_000);
        FileUploadIntentResult uploadIntent = createIntent(context);
        byte[] imageBytes = validPngBytes(32);
        context.storage().addUploadedObject(PNG_MIME_TYPE, imageBytes);

        FileMetadataResult result = context.fileService().completeUpload(USER_ID, uploadIntent.uploadIntentId());

        assertThat(result.fileId()).isNotNull();
        assertThat(result.originalName()).isEqualTo("sample.png");
        assertThat(result.mimeType()).isEqualTo(PNG_MIME_TYPE);
        assertThat(result.fileSize()).isEqualTo(imageBytes.length);
        assertThat(context.files().savedFile().getOwnerUserId()).isEqualTo(USER_ID);
        assertThat(context.files().savedFile().getStorageKey()).startsWith("files/");
        assertThat(context.storage().promotedSourceKey()).startsWith("staging/");
        assertThat(context.storage().promotedDestinationKey()).isEqualTo(
            context.files().savedFile().getStorageKey());
        assertThat(context.intentRepository().intent(uploadIntent.uploadIntentId()).getStatus())
            .isEqualTo(FileUploadIntentStatus.COMPLETED);
        assertThat(context.intentRepository().intent(uploadIntent.uploadIntentId()).getStagingCleanedAt())
            .isNull();
        assertThat(context.storage().deletedStorageKey()).startsWith("staging/");
    }

    @Test
    void 완료_재호출은_같은_fileId를_반환한다() {
        TestContext context = newContext(10_000);
        FileUploadIntentResult uploadIntent = createIntent(context);
        context.storage().addUploadedObject(PNG_MIME_TYPE, validPngBytes(32));

        FileMetadataResult first = context.fileService().completeUpload(USER_ID, uploadIntent.uploadIntentId());
        FileMetadataResult second = context.fileService().completeUpload(USER_ID, uploadIntent.uploadIntentId());

        assertThat(second.fileId()).isEqualTo(first.fileId());
        assertThat(context.files().saveCount()).isEqualTo(1);
    }

    @Test
    void S3에_객체가_없으면_재시도할_수_있도록_intent를_되돌린다() {
        TestContext context = newContext(10_000);
        FileUploadIntentResult uploadIntent = createIntent(context);

        FileBusinessException exception = assertThrows(
            FileBusinessException.class,
            () -> context.fileService().completeUpload(USER_ID, uploadIntent.uploadIntentId()));

        assertThat(exception.getErrorCode()).isEqualTo(FileErrorCode.FILE_UPLOAD_NOT_COMPLETE);
        assertThat(exception.getMessage()).isNull();

        assertThat(context.intentRepository().intent(uploadIntent.uploadIntentId()).getStatus())
            .isEqualTo(FileUploadIntentStatus.PENDING);
        assertThat(context.files().savedFile()).isNull();
    }

    @Test
    void 허용크기를_초과한_실제객체는_저장하지_않고_정리한다() {
        TestContext context = newContext(16);
        FileUploadIntentResult uploadIntent = createIntent(context);
        context.storage().addUploadedObject(PNG_MIME_TYPE, validPngBytes(32));

        FileBusinessException exception = assertThrows(
            FileBusinessException.class,
            () -> context.fileService().completeUpload(USER_ID, uploadIntent.uploadIntentId()));

        assertThat(exception.getErrorCode()).isEqualTo(FileErrorCode.FILE_TOO_LARGE);
        assertThat(exception.getMessage()).isNull();
        assertThat(context.intentRepository().intent(uploadIntent.uploadIntentId()).getStatus())
            .isEqualTo(FileUploadIntentStatus.FAILED);
        assertThat(context.files().savedFile()).isNull();
        assertThat(context.storage().wasDeleted()).isTrue();
    }

    @Test
    void ContentType가_이미지여도_시그니처가_다르면_거부한다() {
        TestContext context = newContext(10_000);
        FileUploadIntentResult uploadIntent = createIntent(context);
        context.storage().addUploadedObject(PNG_MIME_TYPE, new byte[32]);

        FileBusinessException exception = assertThrows(
            FileBusinessException.class,
            () -> context.fileService().completeUpload(USER_ID, uploadIntent.uploadIntentId()));

        assertThat(exception.getErrorCode()).isEqualTo(FileErrorCode.FILE_INVALID_CONTENT);
        assertThat(context.files().savedFile()).isNull();
    }

    @Test
    void DB_완료처리가_실패하면_최종객체를_정리하고_재시도상태로_되돌린다() {
        TestContext context = newContext(10_000);
        FileUploadIntentResult uploadIntent = createIntent(context);
        context.storage().addUploadedObject(PNG_MIME_TYPE, validPngBytes(32));
        context.intentRepository().failNextComplete();

        assertThrows(
            RuntimeException.class,
            () -> context.fileService().completeUpload(USER_ID, uploadIntent.uploadIntentId()));

        assertThat(context.intentRepository().intent(uploadIntent.uploadIntentId()).getStatus())
            .isEqualTo(FileUploadIntentStatus.PENDING);
        assertThat(context.storage().deletedStorageKey()).startsWith("files/");
    }

    @Test
    void 메타데이터와_다운로드_URL은_소유자만_조회할_수_있다() {
        TestContext context = newContext(10_000);
        File file = context.files().saveAndFlush(
            File.create(USER_ID, "files/private-key", "photo.png", PNG_MIME_TYPE, 32L));

        FileBusinessException exception = assertThrows(
            FileBusinessException.class,
            () -> context.fileService().getMetadata(99L, file.getId()));

        assertThat(exception.getErrorCode()).isEqualTo(FileErrorCode.FILE_NOT_FOUND);
        assertThat(exception.getMessage()).isNull();

        FileAccessUrlResult result = context.fileService()
            .createAccessUrl(USER_ID, file.getId(), "inline");

        assertThat(result.accessUrl()).isEqualTo("https://example.com/presigned-get-url");
        assertThat(result.disposition()).isEqualTo("inline");
        assertThat(context.storage().readUrlStorageKey()).isEqualTo("files/private-key");
        assertThat(context.storage().readUrlDisposition()).contains("inline", "photo.png");
    }

    @Test
    void 다른_사용자의_uploadIntent는_완료할_수_없다() {
        TestContext context = newContext(10_000);
        FileUploadIntentResult uploadIntent = context.fileService().createUploadIntent(
            99L,
            new CreateFileUploadIntentCommand("sample.png", PNG_MIME_TYPE));

        FileBusinessException exception = assertThrows(
            FileBusinessException.class,
            () -> context.fileService().completeUpload(USER_ID, uploadIntent.uploadIntentId()));

        assertThat(exception.getErrorCode()).isEqualTo(FileErrorCode.FILE_UPLOAD_INTENT_NOT_FOUND);
    }

    @Test
    void 요청검증예외는_메시지없이_errorCode만_가진다() {
        TestContext context = newContext(10_000);

        RequestValidationException exception = assertThrows(
            RequestValidationException.class,
            () -> context.fileService().createUploadIntent(USER_ID, null));

        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_REQUEST);
        assertThat(exception.getMessage()).isNull();
    }

    private FileUploadIntentResult createIntent(TestContext context) {
        return context.fileService().createUploadIntent(
            USER_ID,
            new CreateFileUploadIntentCommand("sample.png", PNG_MIME_TYPE));
    }

    private TestContext newContext(long maxSizeBytes) {
        FakeFileStorage storage = new FakeFileStorage();
        FakeFileRepository files = new FakeFileRepository();
        FakeFileUploadIntentRepository intents = new FakeFileUploadIntentRepository(files);

        FileProperties fileProperties = new FileProperties();
        fileProperties.setMaxSizeBytes(maxSizeBytes);
        fileProperties.setAllowedMimeTypes(List.of("image/jpeg", "image/png", "image/webp"));
        fileProperties.setUploadIntentTtl(Duration.ofMinutes(15));
        fileProperties.setCleanupDelayMs(300_000);

        S3Properties s3Properties = new S3Properties();
        s3Properties.setBucket("test-bucket");
        s3Properties.setRegion("ap-northeast-2");
        s3Properties.setUploadUrlExpiration(Duration.ofMinutes(10));
        s3Properties.setDownloadUrlExpiration(Duration.ofMinutes(5));

        FileService service = new FileService(
            storage,
            files,
            intents,
            fileProperties,
            s3Properties,
            new ImageSignatureValidator());

        return new TestContext(service, storage, files, intents);
    }

    private byte[] validPngBytes(int size) {
        byte[] bytes = new byte[size];
        byte[] header = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(header, 0, bytes, 0, header.length);
        return bytes;
    }

    private record TestContext(
        FileService fileService,
        FakeFileStorage storage,
        FakeFileRepository files,
        FakeFileUploadIntentRepository intentRepository) {}
}
