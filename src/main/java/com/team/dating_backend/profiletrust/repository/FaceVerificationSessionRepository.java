package com.team.dating_backend.profiletrust.repository;

import com.team.dating_backend.profiletrust.entity.FaceVerificationSession;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FaceVerificationSessionRepository extends JpaRepository<FaceVerificationSession, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<FaceVerificationSession> findFirstByOwnerUserIdOrderByIdDesc(Long ownerUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select session from FaceVerificationSession session
        where session.id = :sessionId and session.ownerUserId = :ownerUserId
        """)
    Optional<FaceVerificationSession> findOwnedForUpdate(
        @Param("sessionId") Long sessionId, @Param("ownerUserId") Long ownerUserId);
}
