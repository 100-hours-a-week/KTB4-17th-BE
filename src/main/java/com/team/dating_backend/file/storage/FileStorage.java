package com.team.dating_backend.file.storage;

import java.time.Duration;
import java.util.Optional;

public interface FileStorage {

    PresignedUploadUrl createUploadUrl(String storageKey, String mimeType, Duration expiration);

    Optional<StoredObjectInfo> inspectUploadedObject(String storageKey);

    void promote(
        String sourceKey,
        String destinationKey,
        String mimeType,
        String sourceVersionToken);

    PresignedReadUrl createReadUrl(
        String storageKey,
        String mimeType,
        String disposition,
        String originalName,
        Duration expiration);

    void delete(String storageKey);
}
