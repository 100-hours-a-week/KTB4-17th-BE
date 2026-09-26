package com.team.dating_backend.file.repository;

import com.team.dating_backend.file.entity.FileUploadIntent;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FileUploadIntentJpaRepository extends JpaRepository<FileUploadIntent, Long> {

    Optional<FileUploadIntent> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select intent
        from FileUploadIntent intent
        where intent.id = :intentId and intent.ownerUserId = :ownerUserId
        """)
    Optional<FileUploadIntent> findByIdAndOwnerUserIdForUpdate(
        @Param("intentId") Long intentId,
        @Param("ownerUserId") Long ownerUserId);

    List<FileUploadIntent> findByExpiresAtBeforeAndStagingCleanedAtIsNullOrderByExpiresAtAsc(
        LocalDateTime now,
        Pageable pageable);

    List<FileUploadIntent> findByStagingCleanedAtIsNotNullAndExpiresAtBeforeOrderByExpiresAtAsc(
        LocalDateTime expiresBefore,
        Pageable pageable);
}
