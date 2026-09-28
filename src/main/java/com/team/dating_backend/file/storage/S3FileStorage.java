package com.team.dating_backend.file.storage;

import com.team.dating_backend.file.config.S3Properties;
import com.team.dating_backend.file.enums.FileErrorCode;
import com.team.dating_backend.file.exception.FileStorageException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Component
@RequiredArgsConstructor
public class S3FileStorage implements FileStorage {

    private static final int SIGNATURE_BYTES_TO_READ = 16;

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3Properties s3Properties;

    @Override
    public PresignedUploadUrl createUploadUrl(
        String storageKey,
        String mimeType,
        Duration expiration) {
        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(s3Properties.getBucket())
                .key(storageKey)
                .contentType(mimeType)
                .build();

            PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(expiration)
                .putObjectRequest(putObjectRequest)
                .build();

            String url = s3Presigner.presignPutObject(presignRequest).url().toString();
            return new PresignedUploadUrl(url, Instant.now().plus(expiration));
        } catch (SdkException exception) {
            throw new FileStorageException(FileErrorCode.FILE_UPLOAD_FAILED, exception);
        }
    }

    @Override
    public Optional<StoredObjectInfo> inspectUploadedObject(String storageKey) {
        try {
            HeadObjectResponse head = s3Client.headObject(
                HeadObjectRequest.builder()
                    .bucket(s3Properties.getBucket())
                    .key(storageKey)
                    .build());

            if (head.contentLength() == 0) {
                return Optional.of(new StoredObjectInfo(
                    head.contentLength(),
                    head.contentType(),
                    head.eTag(),
                    new byte[0]));
            }

            byte[] signatureBytes = s3Client.getObject(
                GetObjectRequest.builder()
                    .bucket(s3Properties.getBucket())
                    .key(storageKey)
                    .range("bytes=0-" + (SIGNATURE_BYTES_TO_READ - 1))
                    .ifMatch(head.eTag())
                    .build(),
                ResponseTransformer.toBytes()).asByteArray();

            return Optional.of(new StoredObjectInfo(
                head.contentLength(),
                head.contentType(),
                head.eTag(),
                signatureBytes));
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404 || exception.statusCode() == 412) {
                return Optional.empty();
            }

            throw new FileStorageException(FileErrorCode.FILE_UPLOAD_FAILED, exception);
        } catch (SdkException exception) {
            throw new FileStorageException(FileErrorCode.FILE_UPLOAD_FAILED, exception);
        }
    }

    @Override
    public void promote(
        String sourceKey,
        String destinationKey,
        String mimeType,
        String sourceVersionToken) {
        try {
            CopyObjectRequest copyRequest = CopyObjectRequest.builder()
                .sourceBucket(s3Properties.getBucket())
                .sourceKey(sourceKey)
                .destinationBucket(s3Properties.getBucket())
                .destinationKey(destinationKey)
                .copySourceIfMatch(sourceVersionToken)
                .metadataDirective(MetadataDirective.REPLACE)
                .contentType(mimeType)
                .build();

            s3Client.copyObject(copyRequest);
        } catch (SdkException exception) {
            throw new FileStorageException(FileErrorCode.FILE_UPLOAD_FAILED, exception);
        }
    }

    @Override
    public PresignedReadUrl createReadUrl(
        String storageKey,
        String mimeType,
        String disposition,
        String originalName,
        Duration expiration) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(s3Properties.getBucket())
                .key(storageKey)
                .responseContentType(mimeType)
                .responseContentDisposition(contentDisposition(disposition, originalName))
                .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(expiration)
                .getObjectRequest(getObjectRequest)
                .build();

            String url = s3Presigner.presignGetObject(presignRequest).url().toString();
            return new PresignedReadUrl(url, Instant.now().plus(expiration));
        } catch (SdkException exception) {
            throw new FileStorageException(FileErrorCode.FILE_READ_URL_FAILED, exception);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            s3Client.deleteObject(
                DeleteObjectRequest.builder()
                    .bucket(s3Properties.getBucket())
                    .key(storageKey)
                    .build());
        } catch (SdkException exception) {
            throw new FileStorageException(FileErrorCode.FILE_DELETE_FAILED, exception);
        }
    }

    public static String contentDisposition(String disposition, String originalName) {
        String encodedName = URLEncoder.encode(originalName, StandardCharsets.UTF_8)
            .replace("+", "%20");
        return disposition + "; filename=\"download\"; filename*=UTF-8''" + encodedName;
    }
}
