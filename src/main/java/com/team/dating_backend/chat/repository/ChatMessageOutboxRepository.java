package com.team.dating_backend.chat.repository;

import com.team.dating_backend.chat.entity.ChatMessageOutbox;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageOutboxRepository extends JpaRepository<ChatMessageOutbox, Long> {

    // Blue 발행 여부와 별개로 RabbitMQ에 아직 발행하지 않은 행을 조회한다.
    List<ChatMessageOutbox> findByRabbitPublishedAtIsNullAndRabbitFailedAtIsNullAndRabbitNextAttemptAtLessThanEqualOrderByRabbitNextAttemptAtAscIdAsc(
        LocalDateTime now, Pageable pageable);
}
