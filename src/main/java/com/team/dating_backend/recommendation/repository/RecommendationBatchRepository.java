package com.team.dating_backend.recommendation.repository;

import com.team.dating_backend.recommendation.entity.RecommendationBatch;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationBatchRepository extends JpaRepository<RecommendationBatch, Long> {

    Optional<RecommendationBatch> findByUserIdAndDeletedAtIsNull(Long userId);
}
