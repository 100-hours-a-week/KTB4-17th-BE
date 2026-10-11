package com.team.dating_backend.aipractice.repository;

import com.team.dating_backend.aipractice.entity.AiPracticeSession;
import com.team.dating_backend.aipractice.enums.AiPracticeSessionStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiPracticeSessionRepository extends JpaRepository<AiPracticeSession, Long> {

    @Query("""
        select session from AiPracticeSession session
        where session.id = :sessionId
          and session.userId = :userId
          and (session.targetMemberId is null or not exists (
              select block.id from UserBlock block
              where block.unblockedAt is null
                and ((block.blockerUserId = session.userId
                      and block.blockedUserId = session.targetMemberId)
                  or (block.blockerUserId = session.targetMemberId
                      and block.blockedUserId = session.userId))
          ))
        """)
    Optional<AiPracticeSession> findAccessibleByIdAndUserId(
        @Param("sessionId") Long sessionId, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select session from AiPracticeSession session
        where session.id = :sessionId
          and session.userId = :userId
          and (session.targetMemberId is null or not exists (
              select block.id from UserBlock block
              where block.unblockedAt is null
                and ((block.blockerUserId = session.userId
                      and block.blockedUserId = session.targetMemberId)
                  or (block.blockerUserId = session.targetMemberId
                      and block.blockedUserId = session.userId))
          ))
        """)
    Optional<AiPracticeSession> findAccessibleByIdAndUserIdForUpdate(
        @Param("sessionId") Long sessionId, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from AiPracticeSession session where session.id = :sessionId")
    Optional<AiPracticeSession> findByIdForUpdate(@Param("sessionId") Long sessionId);

    Optional<AiPracticeSession> findFirstByUserIdAndTargetMemberIdAndStatusOrderByIdDesc(
        Long userId, Long targetMemberId, AiPracticeSessionStatus status);
}
