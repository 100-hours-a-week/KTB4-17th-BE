package com.team.dating_backend.file.storage;

import com.team.dating_backend.file.config.S3Properties;
import com.team.dating_backend.file.exception.FileStorageException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Component
@RequiredArgsConstructor
public class S3FileStorage implements FileStorage {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3Properties s3Properties;

    @Override
    public void upload(String storageKey, byte[] content, String mimeType) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                .bucket(s3Properties.getBucket())
                .key(storageKey)
                .contentType(mimeType)
                .contentLength((long) content.length)
                .build();

            s3Client.putObject(request, RequestBody.fromBytes(content));
        } catch (SdkException exception) {
            throw new FileStorageException("S3 파일 업로드에 실패했습니다.", exception);
        }
    }

    @Override
    public String createReadUrl(String storageKey) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(s3Properties.getBucket())
                .key(storageKey)
                .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(s3Properties.getPresignedUrlExpiration())
                .getObjectRequest(getObjectRequest)
                .build();

            return s3Presigner.presignGetObject(presignRequest).url().toString();
        } catch (SdkException exception) {
            throw new FileStorageException("S3 조회 URL 생성에 실패했습니다.", exception);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(s3Properties.getBucket())
                .key(storageKey)
                .build();

            s3Client.deleteObject(request);
        } catch (SdkException exception) {
            throw new FileStorageException("S3 파일 삭제에 실패했습니다.", exception);
        }
    }
}
