package com.team.dating_backend.auth.repository;

import com.team.dating_backend.auth.entity.RefreshTokenSession;
import com.team.dating_backend.auth.enums.RefreshTokenRevocationReason;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenSessionRepository extends JpaRepository<RefreshTokenSession, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from RefreshTokenSession session where session.sessionKey = :sessionKey")
    Optional<RefreshTokenSession> findBySessionKeyForUpdate(
        @Param("sessionKey") UUID sessionKey);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update RefreshTokenSession session
        set session.revokedAt = :revokedAt, session.revocationReason = :reason
        where session.userId = :userId and session.revokedAt is null
        """)
    int revokeAllActiveByUserId(
        @Param("userId") Long userId,
        @Param("reason") RefreshTokenRevocationReason reason,
        @Param("revokedAt") LocalDateTime revokedAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RefreshTokenSession session where session.expiresAt <= :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
