package com.team.dating_backend.recommendation.repository;

import com.team.dating_backend.user.entity.User;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecommendationCandidateRepository extends JpaRepository<User, Long> {

    @Query("""
        SELECT candidate.id FROM User candidate
        WHERE
        """ + RecommendationEligibilityQuery.ELIGIBLE_CANDIDATE_PREDICATE + """
        ORDER BY candidate.id ASC
        """)
    List<Long> findEligibleCandidateIds(@Param("requesterUserId") Long requesterUserId);
}
