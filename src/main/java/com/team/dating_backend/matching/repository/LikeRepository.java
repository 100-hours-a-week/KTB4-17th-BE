package com.team.dating_backend.matching.repository;

import com.team.dating_backend.matching.entity.Like;
import com.team.dating_backend.matching.enums.LikeStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LikeRepository extends JpaRepository<Like, Long> {

    Optional<Like> findFirstBySenderIdAndReceiverIdAndStatusOrderByIdDesc(
        Long senderId, Long receiverId, LikeStatus status);

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
