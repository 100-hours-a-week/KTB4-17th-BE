package com.team.dating_backend.file.repository;

import com.team.dating_backend.file.entity.File;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileJpaRepository extends JpaRepository<File, Long> {

    Optional<File> findByIdAndDeletedAtIsNull(Long fileId);

    Optional<File> findByIdAndOwnerUserIdAndDeletedAtIsNull(Long fileId, Long ownerUserId);
}
