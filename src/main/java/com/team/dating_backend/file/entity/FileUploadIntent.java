package com.team.dating_backend.file.entity;

import com.team.dating_backend.file.enums.FileErrorCode;
import com.team.dating_backend.file.enums.FileUploadIntentStatus;
import com.team.dating_backend.file.exception.FileBusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Getter
@Entity
@Table(name = "file_upload_intents", uniqueConstraints = {
    @UniqueConstraint(name = "uk_file_upload_intents_staging_key", columnNames = "staging_key"),
    @UniqueConstraint(name = "uk_file_upload_intents_final_key", columnNames = "final_storage_key")
}, indexes = {
    @Index(name = "idx_file_upload_intents_cleanup", columnList = "expires_at, staging_cleaned_at")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FileUploadIntent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "staging_key", nullable = false, length = 512)
    private String stagingKey;

    @Column(name = "final_storage_key", nullable = false, length = 512)
    private String finalStorageKey;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "declared_content_type", nullable = false, length = 100)
    private String declaredContentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FileUploadIntentStatus status;

    @Column(name = "completed_file_id")
    private Long completedFileId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "staging_cleaned_at")
    private LocalDateTime stagingCleanedAt;

    private FileUploadIntent(
        Long ownerUserId,
        String stagingKey,
        String finalStorageKey,
        String originalName,
        String declaredContentType,
        LocalDateTime expiresAt) {
        this.ownerUserId = ownerUserId;
        this.stagingKey = stagingKey;
        this.finalStorageKey = finalStorageKey;
        this.originalName = originalName;
        this.declaredContentType = declaredContentType;
        this.expiresAt = expiresAt;
        this.status = FileUploadIntentStatus.PENDING;
    }

    public static FileUploadIntent create(
        Long ownerUserId,
        String stagingKey,
        String finalStorageKey,
        String originalName,
        String declaredContentType,
        LocalDateTime expiresAt) {
        return new FileUploadIntent(
            ownerUserId,
            stagingKey,
            finalStorageKey,
            originalName,
            declaredContentType,
            expiresAt);
    }

    public void markProcessing() {
        this.status = FileUploadIntentStatus.PROCESSING;
    }

    public void markPending() {
        this.status = FileUploadIntentStatus.PENDING;
    }

    public void markCompleted(Long fileId) {
        if (fileId == null || fileId <= 0) {
            throw new FileBusinessException(FileErrorCode.FILE_INVALID_STATE);
        }

        this.completedFileId = fileId;
        this.status = FileUploadIntentStatus.COMPLETED;
    }

    public void markFailed() {
        this.status = FileUploadIntentStatus.FAILED;
    }

    public void markExpired() {
        if (status == FileUploadIntentStatus.PENDING || status == FileUploadIntentStatus.PROCESSING) {
            this.status = FileUploadIntentStatus.EXPIRED;
        }
    }

    public void markStagingCleaned(LocalDateTime cleanedAt) {
        if (cleanedAt == null) {
            throw new FileBusinessException(FileErrorCode.FILE_INVALID_STATE);
        }

        if (this.stagingCleanedAt == null) {
            this.stagingCleanedAt = cleanedAt;
        }
    }
}
