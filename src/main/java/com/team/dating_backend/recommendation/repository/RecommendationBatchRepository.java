package com.team.dating_backend.recommendation.repository;

import com.team.dating_backend.recommendation.entity.RecommendationBatch;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecommendationBatchRepository extends JpaRepository<RecommendationBatch, Long> {

    Optional<RecommendationBatch> findByUserIdAndDeletedAtIsNull(Long userId);

    @Modifying
    @Query("""
        UPDATE RecommendationBatch batch
        SET batch.deletedAt = :deletedAt
        WHERE batch.userId = :requesterUserId
          AND batch.deletedAt IS NULL
        """)
    int markActiveBatchesDeleted(
        @Param("requesterUserId") Long requesterUserId,
        @Param("deletedAt") LocalDateTime deletedAt);

    @Modifying
    @Query("""
        UPDATE RecommendationBatch batch
        SET batch.deletedAt = :deletedAt
        WHERE batch.userId = :requesterUserId
          AND batch.id <> :newBatchId
          AND batch.deletedAt IS NULL
        """)
    int markPreviousBatchesDeleted(
        @Param("requesterUserId") Long requesterUserId,
        @Param("newBatchId") Long newBatchId,
        @Param("deletedAt") LocalDateTime deletedAt);
}
