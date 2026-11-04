package com.team.dating_backend.notification.repository;

import com.team.dating_backend.notification.entity.Notification;
import com.team.dating_backend.notification.enums.NotificationType;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query("""
        select notification
        from Notification notification
        where notification.sourceEventId = :sourceEventId
          and notification.user.id = :userId
          and notification.type = :type
        """)
    Optional<Notification> findBySourceEventIdAndUserIdAndType(
        @Param("sourceEventId") String sourceEventId,
        @Param("userId") Long userId,
        @Param("type") NotificationType type);

    @Query("""
        select notification
        from Notification notification
        where notification.user.id = :userId
          and notification.deletedAt is null
        order by notification.createdAt desc, notification.id desc
        """)
    List<Notification> findVisibleByUserId(
        @Param("userId") Long userId,
        Pageable pageable);

    @Query("""
        select notification
        from Notification notification
        where notification.user.id = :userId
          and notification.deletedAt is null
          and (
            notification.createdAt < :cursorCreatedAt
            or (notification.createdAt = :cursorCreatedAt and notification.id < :cursorId)
          )
        order by notification.createdAt desc, notification.id desc
        """)
    List<Notification> findVisibleByUserIdBeforeCursor(
        @Param("userId") Long userId,
        @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
        @Param("cursorId") Long cursorId,
        Pageable pageable);

    @Query("""
        select notification
        from Notification notification
        where notification.user.id = :userId
          and notification.deletedAt is null
          and notification.type in :types
        order by notification.createdAt desc, notification.id desc
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
          and (
            notification.createdAt < :cursorCreatedAt
            or (notification.createdAt = :cursorCreatedAt and notification.id < :cursorId)
          )
        order by notification.createdAt desc, notification.id desc
        """)
    List<Notification> findVisibleByUserIdAndTypesBeforeCursor(
        @Param("userId") Long userId,
        @Param("types") List<NotificationType> types,
        @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
        @Param("cursorId") Long cursorId,
        Pageable pageable);

    @Query("""
        select count(notification.id)
        from Notification notification
        where notification.user.id = :userId
          and notification.deletedAt is null
          and notification.readAt is null
        """)
    long countVisibleUnreadByUserId(@Param("userId") Long userId);

    @Modifying(flushAutomatically = true)
    @Query("""
        update Notification notification
        set notification.readAt = :readAt
        where notification.user.id = :userId
          and notification.type = com.team.dating_backend.notification.enums.NotificationType.CHAT_MESSAGE
          and notification.targetType = 'CHAT_ROOM'
          and notification.targetId = :chatRoomId
          and notification.readAt is null
          and notification.deletedAt is null
        """)
    int markVisibleUnreadChatNotificationsAsRead(
        @Param("userId") Long userId,
        @Param("chatRoomId") Long chatRoomId,
        @Param("readAt") LocalDateTime readAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select notification
        from Notification notification
        where notification.id = :notificationId
        """)
    Optional<Notification> findByIdForUpdate(@Param("notificationId") Long notificationId);
}
