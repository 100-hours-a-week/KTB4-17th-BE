package com.team.dating_backend.file.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.team.dating_backend.file.config.S3Properties;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

class S3FileStorageTest {

    @Test
    void 다운로드_URL의_만료시각은_SDK_서명결과를_그대로_사용한다() throws Exception {
        S3Presigner presigner = mock(S3Presigner.class);
        PresignedGetObjectRequest signed = mock(PresignedGetObjectRequest.class);
        Instant expiration = Instant.parse("2026-11-04T03:05:00Z");
        given(signed.url()).willReturn(URI.create("https://example.com/signed-image").toURL());
        given(signed.expiration()).willReturn(expiration);
        given(presigner.presignGetObject(any(GetObjectPresignRequest.class))).willReturn(signed);
        S3Properties properties = new S3Properties();
        properties.setBucket("test-bucket");
        S3FileStorage storage = new S3FileStorage(mock(S3Client.class), presigner, properties);

        PresignedReadUrl result = storage.createReadUrl("image", "image/png", "inline", "image.png",
            Duration.ofMinutes(5));

        assertThat(result.url()).isEqualTo("https://example.com/signed-image");
        assertThat(result.expiresAt()).isEqualTo(expiration);
    }
}
