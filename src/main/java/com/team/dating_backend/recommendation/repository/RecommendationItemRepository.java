package com.team.dating_backend.recommendation.repository;

import com.team.dating_backend.recommendation.entity.RecommendationItem;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecommendationItemRepository extends JpaRepository<RecommendationItem, Long> {

    List<RecommendationItem> findAllByRecommendationBatchId(Long recommendationBatchId);

    @Modifying
    @Query("DELETE FROM RecommendationItem item WHERE item.recommendationBatchId = :batchId AND item.id IN :ids")
    int deleteItems(@Param("batchId") Long batchId, @Param("ids") List<Long> ids);

    @Query("""
        SELECT item.id FROM RecommendationItem item
        WHERE item.recommendationBatchId = :batchId
        ORDER BY item.id ASC
        """)
    List<Long> findItemIds(@Param("batchId") Long batchId, Pageable pageable);

    boolean existsByRecommendationBatchId(Long recommendationBatchId);

    @Query("""
        SELECT count(item) > 0
        FROM RecommendationItem item
        JOIN RecommendationBatch batch ON batch.id = item.recommendationBatchId
        JOIN User requester ON requester.id = batch.userId
        JOIN User candidate ON candidate.id = item.candidateUserId
        JOIN Profile profile ON profile.user = candidate AND profile.deletedAt IS NULL
        WHERE batch.userId = :requesterUserId
          AND batch.deletedAt IS NULL
          AND requester.status = com.team.dating_backend.user.enums.UserStatus.ACTIVE
          AND candidate.id = :userId
          AND
        """ + RecommendationEligibilityQuery.ELIGIBLE_CANDIDATE_PREDICATE)
    boolean existsEligibleCandidateInActiveBatch(
        @Param("requesterUserId") Long requesterUserId,
        @Param("userId") Long userId);

    @Query("""
        SELECT new com.team.dating_backend.recommendation.repository.RecommendationItemCandidateRow(
            item.id, candidate.id, candidate.birthDate, profile.nickname, profile.job,
            profile.mbti, region.provinceName, region.regionName)
        FROM RecommendationItem item
        JOIN User candidate ON candidate.id = item.candidateUserId
        JOIN Profile profile ON profile.user = candidate AND profile.deletedAt IS NULL
        LEFT JOIN profile.activityRegion region
        WHERE item.recommendationBatchId = :batchId
          AND (:cursorItemId IS NULL OR item.id > :cursorItemId)
          AND
        """ + RecommendationEligibilityQuery.ELIGIBLE_CANDIDATE_PREDICATE + """
        ORDER BY item.id ASC
        """)
    List<RecommendationItemCandidateRow> findEligibleItems(
        @Param("batchId") Long batchId,
        @Param("requesterUserId") Long requesterUserId,
        @Param("cursorItemId") Long cursorItemId,
        Pageable pageable);
}
