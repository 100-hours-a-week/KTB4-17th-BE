package com.team.dating_backend.recommendation.repository;

import com.team.dating_backend.recommendation.entity.RecommendationBatch;
import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RecommendationBatchRepository extends JpaRepository<RecommendationBatch, Long> {

    Optional<RecommendationBatch> findByUserIdAndDeletedAtIsNull(Long userId);

    @Query("""
        SELECT batch.id FROM RecommendationBatch batch
        WHERE batch.deletedAt IS NOT NULL
        ORDER BY batch.id ASC
        """)
    List<Long> findDeletedBatchIds(Pageable pageable);
}
