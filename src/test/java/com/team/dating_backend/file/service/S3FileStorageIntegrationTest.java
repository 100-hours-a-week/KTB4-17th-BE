package com.team.dating_backend.file.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.file.config.S3ClientConfig;
import com.team.dating_backend.file.config.S3Properties;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

@Tag("s3-integration")
@SpringBootTest(classes = S3FileStorageIntegrationTest.S3StorageTestConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class S3FileStorageIntegrationTest {

    private static final String MIME_TYPE = "image/png";

    private static final byte[] TEST_IMAGE = createTestImage();

    private static byte[] createTestImage() {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFF112233);

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("PNG 인코더를 찾을 수 없습니다.");
            }

            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException("테스트 PNG 생성에 실패했습니다.", exception);
        }
    }

    @Autowired
    private S3FileStorage s3FileStorage;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private S3Properties s3Properties;

    @Test
    void 실제_S3에_Presigned_PUT으로_업로드하고_검증된_객체를_다운로드한다() throws Exception {
        // Given
        String objectId = UUID.randomUUID().toString();
        String stagingKey = "integration-tests/staging/" + objectId;
        String finalKey = "integration-tests/files/" + objectId;

        try {
            // When
            var uploadUrl = s3FileStorage.createUploadUrl(
                stagingKey,
                MIME_TYPE,
                Duration.ofMinutes(5));
            HttpResponse<String> uploadResponse = HttpClient.newHttpClient()
                .send(
                    HttpRequest.newBuilder(URI.create(uploadUrl.url()))
                        .header("Content-Type", MIME_TYPE)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(TEST_IMAGE))
                        .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(uploadResponse.statusCode()).isEqualTo(200);

            var uploadedObject = s3FileStorage.inspectUploadedObject(stagingKey).orElseThrow();

            // Then
            HeadObjectResponse savedObject = s3Client.headObject(
                HeadObjectRequest.builder()
                    .bucket(s3Properties.getBucket())
                    .key(stagingKey)
                    .build());

            assertThat(savedObject.contentType()).isEqualTo(MIME_TYPE);
            assertThat(savedObject.contentLength()).isEqualTo((long) TEST_IMAGE.length);
            assertThat(uploadedObject.signatureBytes()).contains(TEST_IMAGE[0], TEST_IMAGE[1]);

            // When
            s3FileStorage.promote(stagingKey, finalKey, MIME_TYPE, uploadedObject.versionToken());
            var readUrl = s3FileStorage.createReadUrl(
                finalKey,
                MIME_TYPE,
                "attachment",
                "integration.png",
                Duration.ofMinutes(5));

            // Then
            HttpResponse<byte[]> response = HttpClient.newHttpClient()
                .send(
                    HttpRequest.newBuilder(URI.create(readUrl.url())).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).containsExactly(TEST_IMAGE);

            BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.body()));

            assertThat(image).isNotNull();
            assertThat(image.getWidth()).isEqualTo(1);
            assertThat(image.getHeight()).isEqualTo(1);
        } finally {
            s3FileStorage.delete(stagingKey);
            s3FileStorage.delete(finalKey);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(S3Properties.class)
    @Import({S3ClientConfig.class, S3FileStorage.class})
    static class S3StorageTestConfiguration {}
}
