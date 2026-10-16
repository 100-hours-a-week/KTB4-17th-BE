package com.team.dating_backend.matching.entity;

import com.team.dating_backend.matching.enums.MatchEndReason;
import com.team.dating_backend.matching.enums.MatchStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "matches",
    indexes = @Index(
        name = "idx_matches_sender_receiver_status",
        columnList = "sender_id,receiver_id,status"
    )
)
public class Match {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    @Column(name = "receiver_id", nullable = false)
    private Long receiverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MatchStatus status;

    @Column(name = "matched_at", nullable = false)
    private LocalDateTime matchedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "end_reason", length = 30)
    private MatchEndReason endReason;

    public Match(Long senderId, Long receiverId, LocalDateTime matchedAt) {
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.status = MatchStatus.ACTIVE;
        this.matchedAt = matchedAt;
    }

    public void end(MatchEndReason reason, LocalDateTime endedAt) {
        Objects.requireNonNull(reason);
        Objects.requireNonNull(endedAt);
        if (status == MatchStatus.ACTIVE) {
            status = MatchStatus.ENDED;
            endReason = reason;
            this.endedAt = endedAt;
        }
    }
}
