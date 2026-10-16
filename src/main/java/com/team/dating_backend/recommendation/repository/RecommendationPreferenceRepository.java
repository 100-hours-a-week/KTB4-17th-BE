package com.team.dating_backend.recommendation.repository;

import com.team.dating_backend.recommendation.entity.RecommendationPreference;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationPreferenceRepository extends JpaRepository<RecommendationPreference, Long> {}
