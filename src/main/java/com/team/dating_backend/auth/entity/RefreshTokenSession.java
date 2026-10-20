package com.team.dating_backend.auth.entity;

import com.team.dating_backend.auth.enums.RefreshTokenRevocationReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(
    name = "refresh_token_sessions",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_refresh_token_sessions_session_key",
            columnNames = "session_key"
        ),
        @UniqueConstraint(
            name = "uk_refresh_token_sessions_current_token_hash",
            columnNames = "current_token_hash"
        )
    },
    indexes = {
        @Index(name = "idx_refresh_token_sessions_user_id", columnList = "user_id"),
        @Index(name = "idx_refresh_token_sessions_expires_at", columnList = "expires_at")
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshTokenSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_key", nullable = false, updatable = false)
    private UUID sessionKey;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "current_token_hash", nullable = false, columnDefinition = "binary(32)")
    private byte[] currentTokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_rotated_at", nullable = false)
    private LocalDateTime lastRotatedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revocation_reason", length = 32)
    private RefreshTokenRevocationReason revocationReason;

    public static RefreshTokenSession create(
        UUID sessionKey,
        Long userId,
        byte[] currentTokenHash,
        LocalDateTime createdAt,
        LocalDateTime expiresAt) {
        RefreshTokenSession session = new RefreshTokenSession();
        session.sessionKey = sessionKey;
        session.userId = userId;
        session.currentTokenHash = currentTokenHash.clone();
        session.createdAt = createdAt;
        session.lastRotatedAt = createdAt;
        session.expiresAt = expiresAt;
        return session;
    }

    public boolean isActiveAt(LocalDateTime now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public boolean matches(byte[] tokenHash) {
        return MessageDigest.isEqual(currentTokenHash, tokenHash);
    }

    public void rotate(byte[] tokenHash, LocalDateTime rotatedAt) {
        currentTokenHash = tokenHash.clone();
        lastRotatedAt = rotatedAt;
    }

    public void revoke(RefreshTokenRevocationReason reason, LocalDateTime now) {
        if (revokedAt == null) {
            revokedAt = now;
            revocationReason = reason;
        }
    }
}
