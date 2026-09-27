package com.team.dating_backend.aipractice.repository;

import com.team.dating_backend.aipractice.entity.AiPracticeOutbox;
import com.team.dating_backend.aipractice.enums.AiPracticeOutboxCommandType;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiPracticeOutboxRepository extends JpaRepository<AiPracticeOutbox, Long> {

    List<AiPracticeOutbox> findByPublishedAtIsNullAndFailedAtIsNullAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
        LocalDateTime dueAt, Pageable pageable);

    boolean existsBySessionIdAndCommandType(
        Long sessionId, AiPracticeOutboxCommandType commandType);
}
