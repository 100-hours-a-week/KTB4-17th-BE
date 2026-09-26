package com.team.dating_backend.file.entity;

import com.team.dating_backend.file.enums.FileErrorCode;
import com.team.dating_backend.file.exception.FileBusinessException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Getter
@Entity
@Table(name = "files", uniqueConstraints = {
    @UniqueConstraint(name = "uk_files_storage_key", columnNames = "storage_key")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class File {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    private File(
        Long ownerUserId,
        String storageKey,
        String originalName,
        String mimeType,
        long fileSize) {
        this.ownerUserId = ownerUserId;
        this.storageKey = storageKey;
        this.originalName = originalName;
        this.mimeType = mimeType;
        this.fileSize = fileSize;
    }

    public static File create(
        Long ownerUserId,
        String storageKey,
        String originalName,
        String mimeType,
        long fileSize) {
        return new File(ownerUserId, storageKey, originalName, mimeType, fileSize);
    }

    public void markDeleted(LocalDateTime deletedAt) {
        if (deletedAt == null) {
            throw new FileBusinessException(FileErrorCode.FILE_INVALID_STATE);
        }

        if (this.deletedAt == null) {
            this.deletedAt = deletedAt;
        }
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
