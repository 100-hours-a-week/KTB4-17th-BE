package com.team.dating_backend.aipractice.repository;

import com.team.dating_backend.aipractice.entity.AiPracticeChat;
import com.team.dating_backend.aipractice.enums.AiPracticeChatStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiPracticeChatRepository extends JpaRepository<AiPracticeChat, Long> {

    Optional<AiPracticeChat> findBySessionIdAndClientMessageId(
        Long sessionId, UUID clientMessageId);

    Optional<AiPracticeChat> findByIdAndSessionId(Long id, Long sessionId);

    boolean existsBySessionIdAndStatus(Long sessionId, AiPracticeChatStatus status);

    List<AiPracticeChat> findAllBySessionIdAndStatus(
        Long sessionId, AiPracticeChatStatus status);

    List<AiPracticeChat> findBySessionIdOrderByIdAsc(Long sessionId, Pageable pageable);

    List<AiPracticeChat> findBySessionIdAndIdGreaterThanOrderByIdAsc(
        Long sessionId, Long cursor, Pageable pageable);

    @Query("""
        select count(chat) from AiPracticeChat chat
        join chat.session session
        where session.userId = :userId
        and chat.usageDate = :usageDate
        and chat.status in :statuses
        """)
    long countReservedAndCompletedUsage(
        @Param("userId") Long userId,
        @Param("usageDate") LocalDate usageDate,
        @Param("statuses") Set<AiPracticeChatStatus> statuses);

    long countBySessionIdAndStatus(Long sessionId, AiPracticeChatStatus status);

    @Query("""
        select count(chat) from AiPracticeChat chat
        join chat.session session
        where session.userId = :userId
        and chat.usageDate = :usageDate
        and chat.status = :status
        """)
    long countUsageByUserIdAndUsageDateAndStatus(
        @Param("userId") Long userId,
        @Param("usageDate") LocalDate usageDate,
        @Param("status") AiPracticeChatStatus status);
}
