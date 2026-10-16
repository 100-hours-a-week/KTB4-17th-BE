package com.team.dating_backend.recommendation.repository;

import com.team.dating_backend.recommendation.entity.RecommendationPass;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationPassRepository extends JpaRepository<RecommendationPass, Long> {

    Optional<RecommendationPass> findByPasserUserIdAndPassedUserId(
        Long passerUserId, Long passedUserId);
}
