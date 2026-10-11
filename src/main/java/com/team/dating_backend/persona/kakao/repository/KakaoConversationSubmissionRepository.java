package com.team.dating_backend.persona.kakao.repository;

import com.team.dating_backend.persona.kakao.entity.KakaoConversationSubmission;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KakaoConversationSubmissionRepository
    extends
        JpaRepository<KakaoConversationSubmission, String> {

    @Modifying
    @Query(value = """
        INSERT INTO persona_kakao_submissions
            (id, user_id, idempotency_key, request_fingerprint, submission_status,
             analyzed_count, created_at)
        VALUES (:id, :userId, :requestKey, :fingerprint, 'SUBMITTING', 0, :now)
        ON DUPLICATE KEY UPDATE id = id
        """, nativeQuery = true)
    void insertIfAbsent(@Param("id") String id, @Param("userId") Long userId,
        @Param("requestKey") String requestKey, @Param("fingerprint") String fingerprint,
        @Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<KakaoConversationSubmission> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<KakaoConversationSubmission> findByIdAndUserId(String id, Long userId);
}
