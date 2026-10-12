package com.team.dating_backend.aisimulation.repository;

import com.team.dating_backend.aisimulation.entity.AiSimulationSession;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiSimulationSessionRepository extends JpaRepository<AiSimulationSession, Long> {

    interface ParticipantPair {
        Long getUserId();

        Long getTargetUserId();
    }

    @Query("""
        select session.userId as userId, session.targetUserId as targetUserId
        from AiSimulationSession session
        where session.id = :id and (session.userId = :userId or session.targetUserId = :userId)
        """)
    Optional<ParticipantPair> findPairByIdAndParticipant(@Param("id") Long id, @Param("userId") Long userId);

    @Query("""
        select session from AiSimulationSession session
        where (session.userId = :userId and session.targetUserId = :targetUserId)
            or (session.userId = :targetUserId and session.targetUserId = :userId)
        order by session.id desc
        """)
    List<AiSimulationSession> findLatestByUserPair(@Param("userId") Long userId,
        @Param("targetUserId") Long targetUserId, Pageable pageable);

    @Query(
        """
            select session from AiSimulationSession session
            where (session.userId = :userId and session.targetUserId = :targetUserId)
                or (session.userId = :targetUserId and session.targetUserId = :userId)
            order by case
                when session.status = com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus.COMPLETED then 0
                when session.status = com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus.IN_PROGRESS then 1
                else 2 end,
                session.finishedAt desc, session.id desc
            """
    )
    List<AiSimulationSession> findForReuseByUserPair(@Param("userId") Long userId,
        @Param("targetUserId") Long targetUserId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from AiSimulationSession session where session.id = :sessionId")
    Optional<AiSimulationSession> findByIdForUpdate(@Param("sessionId") Long sessionId);

    @Query("""
        select session from AiSimulationSession session
        join AiSimulationReport report on report.session = session
        where (session.userId = :userId or session.targetUserId = :userId)
            and session.status = com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus.COMPLETED
            and report.status = com.team.dating_backend.aisimulation.enums.AiSimulationReportStatus.COMPLETED
            and report.deletedAt is null
        order by session.id desc
        """)
    List<AiSimulationSession> findVisibleCompletedByParticipant(@Param("userId") Long userId, Pageable pageable);

    @Query("""
        select session from AiSimulationSession session
        where session.id = :id and (session.userId = :userId or session.targetUserId = :userId)
        """)
    Optional<AiSimulationSession> findByIdAndParticipant(@Param("id") Long id, @Param("userId") Long userId);

    @Query("""
        select session.id from AiSimulationSession session
        where session.status = :status and session.createdAt < :deadline
        order by session.id asc
        """)
    List<Long> findExpiredIds(@Param("status") AiSimulationSessionStatus status,
        @Param("deadline") LocalDateTime deadline, Pageable pageable);
}
