package com.team.dating_backend.user.repository;

import com.team.dating_backend.user.entity.UserBlock;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserBlockRepository extends JpaRepository<UserBlock, Long> {

    @Query("""
        select count(block) > 0 from UserBlock block
        where block.unblockedAt is null
          and ((block.blockerUserId = :senderUserId and block.blockedUserId = :receiverUserId)
            or (block.blockerUserId = :receiverUserId and block.blockedUserId = :senderUserId))
        """)
    boolean existsActiveBlockBetween(
        @Param("senderUserId") Long senderUserId,
        @Param("receiverUserId") Long receiverUserId);

    @Query("""
        select block.blockerUserId from UserBlock block
        where block.unblockedAt is null
          and block.blockedUserId = :blockedUserId
          and block.blockerUserId in :blockerUserIds
        """)
    List<Long> findActiveBlockerUserIds(
        @Param("blockedUserId") Long blockedUserId,
        @Param("blockerUserIds") Collection<Long> blockerUserIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<UserBlock> findByBlockerUserIdAndBlockedUserIdAndUnblockedAtIsNull(
        Long blockerUserId,
        Long blockedUserId);
}
