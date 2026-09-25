package com.team.dating_backend.recommendation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "recommendation_passes")
public class RecommendationPass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "passer_user_id", nullable = false)
    private Long passerUserId;

    @Column(name = "passed_user_id", nullable = false)
    private Long passedUserId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
