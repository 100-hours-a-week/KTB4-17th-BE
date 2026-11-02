package com.team.dating_backend.aipractice.repository;

import com.team.dating_backend.aipractice.entity.AiPracticeOutbox;
import com.team.dating_backend.aipractice.enums.AiPracticeOutboxCommandType;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiPracticeOutboxRepository extends JpaRepository<AiPracticeOutbox, Long> {

    List<AiPracticeOutbox> findByPublishedAtIsNullAndFailedAtIsNullAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
        LocalDateTime dueAt, Pageable pageable);

    @Query(value = """
        SELECT * FROM ai_practice_outbox
        WHERE published_at IS NULL AND failed_at IS NULL
            AND next_attempt_at <= :dueAt
            AND (processing_token IS NULL OR processing_until <= UTC_TIMESTAMP(6))
        ORDER BY next_attempt_at ASC, id ASC
        """, nativeQuery = true)
    List<AiPracticeOutbox> findAvailable(
        @Param("dueAt") LocalDateTime dueAt,
        Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE ai_practice_outbox
        SET processing_token = :token,
            processing_until = TIMESTAMPADD(MICROSECOND, :durationMicros, UTC_TIMESTAMP(6))
        WHERE id = :id AND published_at IS NULL AND failed_at IS NULL
            AND next_attempt_at <= :dueAt
            AND (processing_token IS NULL OR processing_until <= UTC_TIMESTAMP(6))
        """, nativeQuery = true)
    int claim(
        @Param("id") Long id,
        @Param("token") String token,
        @Param("dueAt") LocalDateTime dueAt,
        @Param("durationMicros") long durationMicros);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE ai_practice_outbox
        SET processing_until = TIMESTAMPADD(MICROSECOND, :durationMicros, UTC_TIMESTAMP(6))
        WHERE id = :id AND published_at IS NULL AND failed_at IS NULL
            AND processing_token = :token AND processing_until > UTC_TIMESTAMP(6)
        """, nativeQuery = true)
    int renew(
        @Param("id") Long id,
        @Param("token") String token,
        @Param("durationMicros") long durationMicros);

    boolean existsBySessionIdAndCommandType(
        Long sessionId, AiPracticeOutboxCommandType commandType);
}
