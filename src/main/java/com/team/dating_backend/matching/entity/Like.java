package com.team.dating_backend.matching.entity;

import com.team.dating_backend.matching.enums.LikeStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity(name = "MemberLike")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "likes")
public class Like {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    @Column(name = "receiver_id", nullable = false)
    private Long receiverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LikeStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    public Like(Long senderId, Long receiverId, LocalDateTime createdAt) {
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.status = LikeStatus.PENDING;
        this.createdAt = createdAt;
    }

    public void resolve(LikeStatus resolvedStatus, LocalDateTime resolvedAt) {
        if (status != LikeStatus.PENDING || resolvedStatus == LikeStatus.PENDING) {
            throw new IllegalStateException("Only a pending like can be resolved");
        }
        status = resolvedStatus;
        this.resolvedAt = resolvedAt;
    }
}
