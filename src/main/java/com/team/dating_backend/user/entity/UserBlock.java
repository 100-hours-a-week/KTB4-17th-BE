package com.team.dating_backend.user.entity;

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
@Table(name = "user_blocks")
public class UserBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "blocker_user_id", nullable = false)
    private Long blockerUserId;

    @Column(name = "blocked_user_id", nullable = false)
    private Long blockedUserId;

    @Column(name = "blocked_at", nullable = false)
    private LocalDateTime blockedAt;

    @Column(name = "unblocked_at")
    private LocalDateTime unblockedAt;

    public UserBlock(Long blockerUserId, Long blockedUserId, LocalDateTime blockedAt) {
        this.blockerUserId = blockerUserId;
        this.blockedUserId = blockedUserId;
        this.blockedAt = blockedAt;
    }
}
