package com.team.dating_backend.file.support;

import com.team.dating_backend.file.exception.FileStorageException;
import com.team.dating_backend.file.storage.FileStorage;

public final class FailingFileStorage implements FileStorage {

    private boolean uploadAttempted;

    @Override
    public void upload(String storageKey, byte[] content, String mimeType) {
        uploadAttempted = true;
        throw new FileStorageException("파일 저장소 업로드에 실패했습니다.");
    }

    @Override
    public String createReadUrl(String storageKey) {
        throw new FileStorageException("파일 조회 URL 생성에 실패했습니다.");
    }

    @Override
    public void delete(String storageKey) {
        // 이 Fake는 upload()와 createReadUrl() 실패만 시뮬레이션한다.
    }

    public boolean wasUploadAttempted() {
        return uploadAttempted;
    }
}
