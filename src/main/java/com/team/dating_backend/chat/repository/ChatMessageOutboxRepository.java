package com.team.dating_backend.chat.repository;

import com.team.dating_backend.chat.entity.ChatMessageOutbox;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageOutboxRepository extends JpaRepository<ChatMessageOutbox, Long> {

    List<ChatMessageOutbox> findByPublishedAtIsNullAndFailedAtIsNullAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
        LocalDateTime now, Pageable pageable);
}
