package com.team.dating_backend.matching.repository;

import com.team.dating_backend.matching.entity.Like;
import com.team.dating_backend.matching.enums.LikeStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LikeRepository extends JpaRepository<Like, Long> {

    Optional<Like> findFirstBySenderIdAndReceiverIdAndStatusOrderByIdDesc(
        Long senderId, Long receiverId, LikeStatus status);

    Optional<Like> findFirstBySenderIdAndReceiverIdAndStatusOrderByIdAsc(
        Long senderId, Long receiverId, LikeStatus status);

    @Query("""
        SELECT new com.team.dating_backend.matching.repository.SentLikeItem(
            memberLike.id, receiver.id, receiver.birthDate, profile.nickname, profile.job,
            region.provinceName, region.regionName, memberLike.status, memberLike.createdAt)
        FROM MemberLike memberLike
        JOIN User receiver ON receiver.id = memberLike.receiverId
        JOIN Profile profile ON profile.user = receiver AND profile.deletedAt IS NULL
        LEFT JOIN profile.activityRegion region
        WHERE memberLike.senderId = :senderId
          AND memberLike.status = com.team.dating_backend.matching.enums.LikeStatus.PENDING
          AND receiver.status = com.team.dating_backend.user.enums.UserStatus.ACTIVE
          AND (:cursor IS NULL OR memberLike.id < :cursor)
          AND NOT EXISTS (
              SELECT userBlock.id
              FROM UserBlock userBlock
              WHERE userBlock.unblockedAt IS NULL
                AND ((userBlock.blockerUserId = :senderId
                    AND userBlock.blockedUserId = receiver.id)
                  OR (userBlock.blockerUserId = receiver.id
                    AND userBlock.blockedUserId = :senderId))
          )
        ORDER BY memberLike.id DESC
        """)
    List<SentLikeItem> findSentPendingLikes(
        @Param("senderId") Long senderId,
        @Param("cursor") Long cursor,
        Pageable pageable);

    @Query("""
        SELECT new com.team.dating_backend.matching.repository.ReceivedLikeItem(
            memberLike.id, sender.id, sender.birthDate, profile.nickname, profile.job,
            region.provinceName, region.regionName, memberLike.status, memberLike.createdAt)
        FROM MemberLike memberLike
        JOIN User sender ON sender.id = memberLike.senderId
        JOIN Profile profile ON profile.user = sender AND profile.deletedAt IS NULL
        LEFT JOIN profile.activityRegion region
        WHERE memberLike.receiverId = :receiverId
          AND memberLike.status = com.team.dating_backend.matching.enums.LikeStatus.PENDING
          AND sender.status = com.team.dating_backend.user.enums.UserStatus.ACTIVE
          AND (:cursor IS NULL OR memberLike.id < :cursor)
          AND NOT EXISTS (
              SELECT userBlock.id
              FROM UserBlock userBlock
              WHERE userBlock.unblockedAt IS NULL
                AND ((userBlock.blockerUserId = :receiverId
                    AND userBlock.blockedUserId = sender.id)
                  OR (userBlock.blockerUserId = sender.id
                    AND userBlock.blockedUserId = :receiverId))
          )
        ORDER BY memberLike.id DESC
        """)
    List<ReceivedLikeItem> findReceivedPendingLikes(
        @Param("receiverId") Long receiverId,
        @Param("cursor") Long cursor,
        Pageable pageable);

    @Modifying
    @Query("""
        update MemberLike memberLike
           set memberLike.status = :resolvedStatus, memberLike.resolvedAt = :resolvedAt
        where memberLike.status = :pendingStatus
          and (memberLike.senderId = :userId or memberLike.receiverId = :userId)
        """)
    int resolvePendingForUser(
        @Param("userId") Long userId,
        @Param("pendingStatus") LikeStatus pendingStatus,
        @Param("resolvedStatus") LikeStatus resolvedStatus,
        @Param("resolvedAt") LocalDateTime resolvedAt);
}
