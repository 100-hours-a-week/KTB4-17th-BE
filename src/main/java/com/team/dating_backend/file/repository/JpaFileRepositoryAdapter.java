package com.team.dating_backend.file.repository;

import com.team.dating_backend.file.entity.File;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JpaFileRepositoryAdapter implements FileRepository {

    private final FileJpaRepository fileJpaRepository;

    @Override
    public File save(File file) {
        return fileJpaRepository.save(file);
    }

    @Override
    public File saveAndFlush(File file) {
        return fileJpaRepository.saveAndFlush(file);
    }

    @Override
    public Optional<File> findActiveById(Long fileId) {
        return fileJpaRepository.findByIdAndDeletedAtIsNull(fileId);
    }

    @Override
    public Optional<File> findActiveByIdAndOwner(Long fileId, Long ownerUserId) {
        return fileJpaRepository.findByIdAndOwnerUserIdAndDeletedAtIsNull(fileId, ownerUserId);
    }
}
