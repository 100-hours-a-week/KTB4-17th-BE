package com.team.dating_backend.notification.repository;

import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.enums.NotificationOutboxStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRequestOutboxRepository extends JpaRepository<NotificationRequestOutbox, Long> {

    @Query("""
        SELECT outbox FROM NotificationRequestOutbox outbox
        WHERE outbox.status = :status AND outbox.nextAttemptAt <= :now
        ORDER BY outbox.nextAttemptAt ASC, outbox.id ASC
        """)
    List<NotificationRequestOutbox> findDue(
        @Param("status") NotificationOutboxStatus status,
        @Param("now") LocalDateTime now,
        Pageable pageable);
}
