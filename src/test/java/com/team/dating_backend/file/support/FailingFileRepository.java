package com.team.dating_backend.file.support;

import com.team.dating_backend.file.entity.File;
import com.team.dating_backend.file.repository.FileRepository;
import java.util.Optional;

public final class FailingFileRepository implements FileRepository {
    private File savedFile;

    @Override
    public File save(File file) {
        throw new RuntimeException("DB 저장 실패");
    }

    @Override
    public Optional<File> findActiveById(Long fileId) {
        if (savedFile == null || !savedFile.getId().equals(fileId) || savedFile.isDeleted()) {
            return Optional.empty();
        }

        return Optional.of(savedFile);
    }

    public File savedFile() {
        return savedFile;
    }
}
