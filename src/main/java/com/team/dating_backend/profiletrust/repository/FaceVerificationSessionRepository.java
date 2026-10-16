package com.team.dating_backend.profiletrust.repository;

import com.team.dating_backend.profiletrust.entity.FaceVerificationSession;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FaceVerificationSessionRepository extends JpaRepository<FaceVerificationSession, Long> {

    @Modifying(flushAutomatically = true)
    @Query("""
        update FaceVerificationSession session set session.verifiedFileId = :fileId
        where session.ownerUserId = :userId and session.uploadIntentId = :uploadIntentId
          and session.photoVersionToken = :versionToken
          and session.status = com.team.dating_backend.profiletrust.enums.VerificationSessionStatus.VERIFIED
          and session.verifiedFileId is null
        """)
    int bindVerifiedFile(@Param("userId") Long userId,
        @Param("uploadIntentId") Long uploadIntentId, @Param("versionToken") String versionToken,
        @Param("fileId") Long fileId);

    @Query("""
        select count(session) > 0 from FaceVerificationSession session
        join ProfileImage image on image.image.id = session.verifiedFileId
        join image.profile profile
        where session.ownerUserId = :userId and profile.user.id = :userId
          and profile.id = :profileId and profile.deletedAt is null
          and session.status = com.team.dating_backend.profiletrust.enums.VerificationSessionStatus.VERIFIED
          and image.frontal = true and image.deletedAt is null
          and image.image.ownerUserId = :userId and image.image.deletedAt is null
        """)
    boolean existsVerifiedProfilePhoto(@Param("userId") Long userId, @Param("profileId") Long profileId);

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
