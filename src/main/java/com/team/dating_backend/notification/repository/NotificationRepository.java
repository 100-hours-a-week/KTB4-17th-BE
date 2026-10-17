package com.team.dating_backend.notification.repository;

import com.team.dating_backend.notification.entity.Notification;
import com.team.dating_backend.notification.enums.NotificationType;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query("""
        select notification
        from Notification notification
        where notification.user.id = :userId
          and notification.deletedAt is null
        order by notification.id desc
        """)
    List<Notification> findVisibleByUserId(
        @Param("userId") Long userId,
        Pageable pageable);

    @Query("""
        select notification
        from Notification notification
        where notification.user.id = :userId
          and notification.deletedAt is null
          and notification.id < :cursor
        order by notification.id desc
        """)
    List<Notification> findVisibleByUserIdAndIdLessThan(
        @Param("userId") Long userId,
        @Param("cursor") Long cursor,
        Pageable pageable);

    @Query("""
        select notification
        from Notification notification
        where notification.user.id = :userId
          and notification.deletedAt is null
          and notification.type in :types
        order by notification.id desc
        """)
    List<Notification> findVisibleByUserIdAndTypes(
        @Param("userId") Long userId,
        @Param("types") List<NotificationType> types,
        Pageable pageable);

    @Query("""
        select notification
        from Notification notification
        where notification.user.id = :userId
          and notification.deletedAt is null
          and notification.type in :types
          and notification.id < :cursor
        order by notification.id desc
        """)
    List<Notification> findVisibleByUserIdAndTypesAndIdLessThan(
        @Param("userId") Long userId,
        @Param("types") List<NotificationType> types,
        @Param("cursor") Long cursor,
        Pageable pageable);

    @Query("""
        select count(notification.id)
        from Notification notification
        where notification.user.id = :userId
          and notification.deletedAt is null
          and notification.readAt is null
        """)
    long countVisibleUnreadByUserId(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select notification
        from Notification notification
        where notification.id = :notificationId
        """)
    Optional<Notification> findByIdForUpdate(@Param("notificationId") Long notificationId);
}
