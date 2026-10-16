package com.team.dating_backend.profiletrust.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.profiletrust.enums.ProfileTrustErrorCode;
import com.team.dating_backend.profiletrust.exception.ProfileTrustBusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class VerificationVideoReaderTest {

    private final VerificationVideoReader reader = new VerificationVideoReader();

    @Test
    void 코덱_파라미터를_정규화하고_내용이_같으면_같은_해시를_반환한다() {
        var first = reader
            .read(new MockMultipartFile("video", "first.webm", "video/webm;codecs=vp8,opus", new byte[]{1}));
        var second = reader.read(new MockMultipartFile("video", "second.webm", "video/webm", new byte[]{1}));
        assertThat(first.contentType()).isEqualTo("video/webm");
        assertThat(first.hash()).hasSize(64).isEqualTo(second.hash());
        assertThat(first.bytes()).containsExactly((byte) 1);
    }

    @Test
    void 영상_파일_최대_크기를_초과하면_거부한다() {
        assertThatThrownBy(() -> reader.read(new MockMultipartFile("video", "large.mp4", "video/mp4",
            new byte[VerificationVideoReader.MAX_VIDEO_BYTES + 1])))
            .isInstanceOfSatisfying(ProfileTrustBusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ProfileTrustErrorCode.VIDEO_TOO_LARGE));
    }

    @Test
    void 빈_영상과_지원하지_않는_타입을_거부한다() {
        assertThatThrownBy(() -> reader.read(new MockMultipartFile("video", new byte[0])))
            .isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> reader.read(new MockMultipartFile("video", "photo.png", "image/png", new byte[]{1})))
            .isInstanceOfSatisfying(ProfileTrustBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ProfileTrustErrorCode.VIDEO_TYPE_NOT_ALLOWED));
    }
}
