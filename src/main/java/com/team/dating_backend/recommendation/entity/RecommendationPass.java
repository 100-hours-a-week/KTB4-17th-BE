package com.team.dating_backend.recommendation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "recommendation_passes",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_recommendation_pass_direction",
        columnNames = {"passer_user_id", "passed_user_id"}
    )
)
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

    public RecommendationPass(Long passerUserId, Long passedUserId, LocalDateTime createdAt) {
        this.passerUserId = passerUserId;
        this.passedUserId = passedUserId;
        this.createdAt = createdAt;
    }
}
