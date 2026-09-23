package com.team.dating_backend.file.storage;

public interface FileStorage {

    void upload(String storageKey, byte[] content, String mimeType);

    String createReadUrl(String storageKey);

    void delete(String storageKey);
}
