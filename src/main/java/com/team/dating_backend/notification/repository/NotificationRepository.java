package com.team.dating_backend.notification.repository;

import com.team.dating_backend.notification.entity.Notification;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select notification
        from Notification notification
        where notification.id = :notificationId
        """)
    Optional<Notification> findByIdForUpdate(@Param("notificationId") Long notificationId);
}
