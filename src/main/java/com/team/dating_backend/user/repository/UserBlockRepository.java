package com.team.dating_backend.user.repository;

import com.team.dating_backend.user.entity.UserBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserBlockRepository extends JpaRepository<UserBlock, Long> {

    @Query("""
        select count(block) > 0 from UserBlock block
        where block.unblockedAt is null
          and ((block.blockerUserId = :senderMemberId and block.blockedUserId = :receiverMemberId)
            or (block.blockerUserId = :receiverMemberId and block.blockedUserId = :senderMemberId))
        """)
    boolean existsActiveBlockBetween(
        @Param("senderMemberId") Long senderMemberId,
        @Param("receiverMemberId") Long receiverMemberId);
}
