package com.team.dating_backend.recommendation.repository;

import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import com.team.dating_backend.user.enums.Gender;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.LocalDate;
import java.util.List;

public class RecommendationCandidateRandomRepositoryImpl
    implements
        RecommendationCandidateRandomRepository {

    private static final int MAX_RANDOM_CANDIDATES = 300;

    private static final String FIND_ELIGIBLE_CANDIDATES = """
        SELECT /*+ NO_BNL() */
            candidate.id,
            candidate.recommendation_random_key
        FROM users candidate
        STRAIGHT_JOIN profiles profile
            ON profile.user_id = candidate.id
            AND profile.deleted_at IS NULL
        WHERE candidate.gender IN (:candidateGenders)
          AND (:birthDateAfter IS NULL OR candidate.birth_date > :birthDateAfter)
          AND (:birthDateOnOrBefore IS NULL OR candidate.birth_date <= :birthDateOnOrBefore)
          AND (:minHeight IS NULL OR profile.height >= :minHeight)
          AND (:maxHeight IS NULL OR profile.height <= :maxHeight)
          AND (:religionUnrestricted = TRUE OR profile.religion IN (:religion))
          AND (:drinkingUnrestricted = TRUE OR profile.drinking IN (:drinking))
          AND (:smokingUnrestricted = TRUE OR profile.smoking IN (:smoking))
          AND candidate.status = 'ACTIVE'
          AND candidate.id <> :requesterUserId
          AND (candidate.recommendation_random_key > :afterRandomKey
            OR (candidate.recommendation_random_key = :afterRandomKey
              AND candidate.id > :afterUserId))
          AND NOT EXISTS (
              SELECT 1
              FROM user_blocks sent_block
              WHERE sent_block.blocker_user_id = :requesterUserId
                AND sent_block.blocked_user_id = candidate.id
          )
          AND NOT EXISTS (
              SELECT 1
              FROM user_blocks received_block
              WHERE received_block.blocker_user_id = candidate.id
                AND received_block.blocked_user_id = :requesterUserId
          )
          AND NOT EXISTS (
              SELECT 1
              FROM likes sent_rejected_like
              WHERE sent_rejected_like.status = 'REJECTED'
                AND sent_rejected_like.sender_id = :requesterUserId
                AND sent_rejected_like.receiver_id = candidate.id
          )
          AND NOT EXISTS (
              SELECT 1
              FROM likes received_rejected_like
              WHERE received_rejected_like.status = 'REJECTED'
                AND received_rejected_like.sender_id = candidate.id
                AND received_rejected_like.receiver_id = :requesterUserId
          )
          AND NOT EXISTS (
              SELECT 1
              FROM likes pending_like
              WHERE pending_like.status = 'PENDING'
                AND pending_like.sender_id = :requesterUserId
                AND pending_like.receiver_id = candidate.id
          )
          AND NOT EXISTS (
              SELECT 1
              FROM matches sent_match
              WHERE sent_match.sender_id = :requesterUserId
                AND sent_match.receiver_id = candidate.id
          )
          AND NOT EXISTS (
              SELECT 1
              FROM matches received_match
              WHERE received_match.sender_id = candidate.id
                AND received_match.receiver_id = :requesterUserId
          )
          AND NOT EXISTS (
              SELECT 1
              FROM recommendation_passes recommendation_pass
              WHERE recommendation_pass.passer_user_id = :requesterUserId
                AND recommendation_pass.passed_user_id = candidate.id
          )
        ORDER BY candidate.recommendation_random_key ASC, candidate.id ASC
        """;

    private final EntityManager entityManager;

    public RecommendationCandidateRandomRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<RecommendationCandidatePosition> findEligibleCandidatePositionsByGenders(
        Long requesterUserId,
        List<Gender> candidateGenders,
        LocalDate birthDateAfter,
        LocalDate birthDateOnOrBefore,
        Short minHeight,
        Short maxHeight,
        boolean religionUnrestricted,
        List<Religion> religion,
        boolean drinkingUnrestricted,
        List<Drinking> drinking,
        boolean smokingUnrestricted,
        List<Smoking> smoking,
        Long afterRandomKey,
        Long afterUserId,
        int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }

        Query query = entityManager.createNativeQuery(FIND_ELIGIBLE_CANDIDATES);
        query.setParameter("requesterUserId", requesterUserId);
        query.setParameter("candidateGenders", names(candidateGenders));
        query.setParameter("birthDateAfter", birthDateAfter);
        query.setParameter("birthDateOnOrBefore", birthDateOnOrBefore);
        query.setParameter("minHeight", minHeight);
        query.setParameter("maxHeight", maxHeight);
        query.setParameter("religionUnrestricted", religionUnrestricted);
        query.setParameter("religion", names(religion));
        query.setParameter("drinkingUnrestricted", drinkingUnrestricted);
        query.setParameter("drinking", names(drinking));
        query.setParameter("smokingUnrestricted", smokingUnrestricted);
        query.setParameter("smoking", names(smoking));
        query.setParameter("afterRandomKey", afterRandomKey);
        query.setParameter("afterUserId", afterUserId);
        query.setMaxResults(Math.min(limit, MAX_RANDOM_CANDIDATES));

        return resultRows(query).stream()
            .map(row -> new RecommendationCandidatePosition(
                ((Number) row[0]).longValue(), ((Number) row[1]).longValue()))
            .toList();
    }

    private static List<String> names(List<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> resultRows(Query query) {
        return query.getResultList();
    }
}
