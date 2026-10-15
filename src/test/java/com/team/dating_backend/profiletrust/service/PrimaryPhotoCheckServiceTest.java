package com.team.dating_backend.profiletrust.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.file.config.FileProperties;
import com.team.dating_backend.file.entity.FileUploadIntent;
import com.team.dating_backend.file.enums.FileUploadIntentStatus;
import com.team.dating_backend.file.service.ImageSignatureValidator;
import com.team.dating_backend.file.storage.FileStorage;
import com.team.dating_backend.file.storage.StoredObjectInfo;
import com.team.dating_backend.file.support.FakeFileRepository;
import com.team.dating_backend.file.support.FakeFileUploadIntentRepository;
import com.team.dating_backend.profiletrust.client.PrimaryPhotoAiClient;
import com.team.dating_backend.profiletrust.dto.PrimaryPhotoCheckResult;
import com.team.dating_backend.profiletrust.dto.ai.PrimaryPhotoAiPayloads.FrontalCheckResponse;
import com.team.dating_backend.profiletrust.dto.ai.PrimaryPhotoAiPayloads.SyntheticCheckResponse;
import com.team.dating_backend.profiletrust.enums.ProfileTrustErrorCode;
import com.team.dating_backend.profiletrust.exception.ProfileTrustBusinessException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrimaryPhotoCheckServiceTest {

    private static final Long USER_ID = 41L;
    private static final String CONTENT_TYPE = "image/png";
    private static final String VERSION_TOKEN = "etag-1";
    private static final byte[] IMAGE = {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private final FileStorage storage = mock(FileStorage.class);
    private final PrimaryPhotoAiClient aiClient = mock(PrimaryPhotoAiClient.class);
    private final FakeFileUploadIntentRepository repository = new FakeFileUploadIntentRepository(
        new FakeFileRepository());
    private FileUploadIntent intent;
    private PrimaryPhotoCheckService service;

    @BeforeEach
    void setUp() {
        intent = repository.saveAndFlush(FileUploadIntent.create(
            USER_ID, "staging/photo", "files/photo", "photo.png", CONTENT_TYPE,
            LocalDateTime.now().plusMinutes(10)));
        FileProperties properties = new FileProperties();
        properties.setMaxSizeBytes(10_000);
        given(storage.inspectUploadedObject(intent.getStagingKey())).willReturn(Optional.of(
            new StoredObjectInfo(IMAGE.length, CONTENT_TYPE, VERSION_TOKEN, IMAGE)));
        given(storage.readUploadedObject(intent.getStagingKey(), VERSION_TOKEN)).willReturn(IMAGE);
        service = new PrimaryPhotoCheckService(
            storage, repository, properties, new ImageSignatureValidator(), aiClient);
    }

    @Test
    void 두_검사를_동시에_호출하고_모두_통과하면_사진_버전을_저장한다() {
        CountDownLatch bothStarted = new CountDownLatch(2);
        given(aiClient.checkFrontal(IMAGE, CONTENT_TYPE)).willAnswer(invocation -> {
            bothStarted.countDown();
            assertThat(bothStarted.await(5, TimeUnit.SECONDS)).isTrue();
            return new FrontalCheckResponse("PASS", List.of());
        });
        given(aiClient.checkSynthetic(IMAGE, CONTENT_TYPE)).willAnswer(invocation -> {
            bothStarted.countDown();
            assertThat(bothStarted.await(5, TimeUnit.SECONDS)).isTrue();
            return new SyntheticCheckResponse("CLEAR");
        });

        PrimaryPhotoCheckResult result = service.check(USER_ID, intent.getId());

        assertThat(result.passed()).isTrue();
        assertThat(result.reasonCodes()).isEmpty();
        assertThat(intent.getValidatedVersionToken()).isEqualTo(VERSION_TOKEN);
        assertThat(intent.getStatus()).isEqualTo(FileUploadIntentStatus.PENDING);
    }

    @Test
    void 정면_검사가_실패해도_AI_생성_검사_결과까지_모아_반환한다() {
        given(aiClient.checkFrontal(IMAGE, CONTENT_TYPE)).willReturn(
            new FrontalCheckResponse("RETRY", List.of("NON_FRONTAL_FACE")));
        given(aiClient.checkSynthetic(IMAGE, CONTENT_TYPE)).willReturn(
            new SyntheticCheckResponse("SYNTHETIC_RISK"));

        PrimaryPhotoCheckResult result = service.check(USER_ID, intent.getId());

        assertThat(result.passed()).isFalse();
        assertThat(result.reasonCodes()).containsExactly("NON_FRONTAL_FACE", "SYNTHETIC_RISK");
        assertThat(intent.getStatus()).isEqualTo(FileUploadIntentStatus.FAILED);
        assertThat(intent.getValidatedVersionToken()).isNull();
        verify(storage).delete(intent.getStagingKey());
        verify(storage).delete(intent.getFinalStorageKey());
    }

    @Test
    void 병렬_호출의_예외_ErrorCode를_유지하고_재시도_상태로_되돌린다() {
        ProfileTrustBusinessException failure = new ProfileTrustBusinessException(
            ProfileTrustErrorCode.AI_REQUEST_REJECTED);
        given(aiClient.checkFrontal(IMAGE, CONTENT_TYPE)).willReturn(
            new FrontalCheckResponse("PASS", List.of()));
        given(aiClient.checkSynthetic(IMAGE, CONTENT_TYPE)).willThrow(failure);

        assertThatThrownBy(() -> service.check(USER_ID, intent.getId()))
            .isSameAs(failure);

        assertThat(failure.getMessage()).isNull();
        assertThat(intent.getStatus()).isEqualTo(FileUploadIntentStatus.PENDING);
        assertThat(intent.getValidatedVersionToken()).isNull();
        verify(aiClient).checkFrontal(IMAGE, CONTENT_TYPE);
        verify(aiClient).checkSynthetic(IMAGE, CONTENT_TYPE);
        verify(storage, never()).delete(anyString());
    }
}
