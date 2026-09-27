package com.team.dating_backend.aipractice.repository;

import com.team.dating_backend.aipractice.entity.AiPracticeSession;
import com.team.dating_backend.aipractice.enums.AiPracticeSessionStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiPracticeSessionRepository extends JpaRepository<AiPracticeSession, Long> {

    Optional<AiPracticeSession> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select session from AiPracticeSession session
        where session.id = :sessionId and session.userId = :userId
        """)
    Optional<AiPracticeSession> findByIdAndUserIdForUpdate(
        @Param("sessionId") Long sessionId, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from AiPracticeSession session where session.id = :sessionId")
    Optional<AiPracticeSession> findByIdForUpdate(@Param("sessionId") Long sessionId);

    Optional<AiPracticeSession> findFirstByUserIdAndTargetMemberIdAndStatusOrderByIdDesc(
        Long userId, Long targetMemberId, AiPracticeSessionStatus status);

    List<AiPracticeSession> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    List<AiPracticeSession> findByUserIdAndIdLessThanOrderByIdDesc(
        Long userId, Long cursor, Pageable pageable);
}
