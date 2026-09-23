package com.team.dating_backend.file.support;

import com.team.dating_backend.file.storage.FileStorage;
import java.util.Arrays;

public final class FakeFileStorage implements FileStorage {

    private boolean saved;
    private String savedStorageKey;
    private byte[] savedContent;
    private String savedMimeType;
    private boolean deleted;
    private String deletedStorageKey;
    private boolean readUrlCreated;
    private String readUrlStorageKey;
    private String readUrl = "https://example.com/presigned-file-url";

    @Override
    public void upload(String storageKey, byte[] content, String mimeType) {
        this.saved = true;
        this.savedStorageKey = storageKey;
        this.savedContent = Arrays.copyOf(content, content.length);
        this.savedMimeType = mimeType;
    }

    @Override
    public String createReadUrl(String storageKey) {
        this.readUrlCreated = true;
        this.readUrlStorageKey = storageKey;
        return readUrl;
    }

    @Override
    public void delete(String storageKey) {
        this.deleted = true;
        this.deletedStorageKey = storageKey;
    }

    public boolean wasSaved() {
        return saved;
    }

    public String savedStorageKey() {
        return savedStorageKey;
    }

    public byte[] savedContent() {
        return savedContent == null ? null : Arrays.copyOf(savedContent, savedContent.length);
    }

    public String savedMimeType() {
        return savedMimeType;
    }

    public boolean wasDeleted() {
        return deleted;
    }

    public String deletedStorageKey() {
        return deletedStorageKey;
    }

    public boolean wasReadUrlCreated() {
        return readUrlCreated;
    }

    public String readUrlStorageKey() {
        return readUrlStorageKey;
    }
}
