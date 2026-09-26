package com.team.dating_backend.file.support;

import com.team.dating_backend.file.storage.FileStorage;
import com.team.dating_backend.file.storage.PresignedReadUrl;
import com.team.dating_backend.file.storage.PresignedUploadUrl;
import com.team.dating_backend.file.storage.StoredObjectInfo;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class FakeFileStorage implements FileStorage {

    private final Map<String, StoredObjectInfo> objects = new HashMap<>();
    private String lastUploadKey;
    private String promotedSourceKey;
    private String promotedDestinationKey;
    private String readUrlStorageKey;
    private String readUrlDisposition;
    private boolean deleted;
    private String deletedStorageKey;

    @Override
    public PresignedUploadUrl createUploadUrl(String storageKey, String mimeType, Duration expiration) {
        lastUploadKey = storageKey;
        return new PresignedUploadUrl(
            "https://example.com/presigned-put-url",
            Instant.now().plus(expiration));
    }

    @Override
    public Optional<StoredObjectInfo> inspectUploadedObject(String storageKey) {
        return Optional.ofNullable(objects.get(storageKey));
    }

    @Override
    public void promote(
        String sourceKey,
        String destinationKey,
        String mimeType,
        String sourceVersionToken) {
        StoredObjectInfo source = objects.get(sourceKey);
        if (source == null || !source.versionToken().equals(sourceVersionToken)) {
            throw new IllegalStateException("source object changed");
        }

        promotedSourceKey = sourceKey;
        promotedDestinationKey = destinationKey;
        objects.put(
            destinationKey,
            new StoredObjectInfo(
                source.fileSize(),
                mimeType,
                sourceVersionToken,
                source.signatureBytes()));
    }

    @Override
    public PresignedReadUrl createReadUrl(
        String storageKey,
        String mimeType,
        String disposition,
        String originalName,
        Duration expiration) {
        readUrlStorageKey = storageKey;
        readUrlDisposition = disposition + "; " + originalName;
        return new PresignedReadUrl(
            "https://example.com/presigned-get-url",
            Instant.now().plus(expiration));
    }

    @Override
    public void delete(String storageKey) {
        deleted = true;
        deletedStorageKey = storageKey;
        objects.remove(storageKey);
    }

    public void addUploadedObject(String mimeType, byte[] content) {
        objects.put(
            lastUploadKey,
            new StoredObjectInfo(content.length, mimeType, "etag-1", Arrays.copyOf(content, content.length)));
    }

    public String lastUploadKey() {
        return lastUploadKey;
    }

    public String promotedSourceKey() {
        return promotedSourceKey;
    }

    public String promotedDestinationKey() {
        return promotedDestinationKey;
    }

    public String readUrlStorageKey() {
        return readUrlStorageKey;
    }

    public String readUrlDisposition() {
        return readUrlDisposition;
    }

    public boolean wasDeleted() {
        return deleted;
    }

    public String deletedStorageKey() {
        return deletedStorageKey;
    }
}
