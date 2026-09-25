package com.team.dating_backend.recommendation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "recommendation_items")
public class RecommendationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recommendation_batch_id", nullable = false)
    private Long recommendationBatchId;

    @Column(name = "candidate_user_id", nullable = false)
    private Long candidateUserId;

    @Column(name = "ranking_order", nullable = false)
    private Integer rankingOrder;

    public RecommendationItem(Long recommendationBatchId, Long candidateUserId, Integer rankingOrder) {
        this.recommendationBatchId = recommendationBatchId;
        this.candidateUserId = candidateUserId;
        this.rankingOrder = rankingOrder;
    }
}
