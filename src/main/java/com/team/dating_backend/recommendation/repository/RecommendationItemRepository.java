package com.team.dating_backend.recommendation.repository;

import com.team.dating_backend.recommendation.entity.RecommendationItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecommendationItemRepository extends JpaRepository<RecommendationItem, Long> {

    Optional<RecommendationItem> findByIdAndRecommendationBatchId(Long id, Long recommendationBatchId);

    @Query("""
        SELECT new com.team.dating_backend.recommendation.repository.RecommendationItemCandidateRow(
            item.id, candidate.id, candidate.birthDate, profile.nickname, profile.job,
            profile.mbti, region.provinceName, region.regionName)
        FROM RecommendationItem item
        JOIN User candidate ON candidate.id = item.candidateUserId
        JOIN Profile profile ON profile.user = candidate AND profile.deletedAt IS NULL
        LEFT JOIN profile.activityRegion region
        WHERE item.recommendationBatchId = :batchId
          AND (:cursorRankingOrder IS NULL OR item.rankingOrder > :cursorRankingOrder
            OR (item.rankingOrder = :cursorRankingOrder AND item.id > :cursorItemId))
          AND
        """ + RecommendationEligibilityQuery.ELIGIBLE_CANDIDATE_PREDICATE + """
        ORDER BY item.rankingOrder ASC, item.id ASC
        """)
    List<RecommendationItemCandidateRow> findEligibleItems(
        @Param("batchId") Long batchId,
        @Param("requesterUserId") Long requesterUserId,
        @Param("cursorRankingOrder") Integer cursorRankingOrder,
        @Param("cursorItemId") Long cursorItemId,
        Pageable pageable);
}
