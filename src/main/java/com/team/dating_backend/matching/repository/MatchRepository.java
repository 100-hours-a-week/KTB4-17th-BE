package com.team.dating_backend.matching.repository;

import com.team.dating_backend.matching.entity.Match;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MatchRepository extends JpaRepository<Match, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select memberMatch from Match memberMatch
        where memberMatch.status = com.team.dating_backend.matching.enums.MatchStatus.ACTIVE
          and (
              (memberMatch.senderId = :firstUserId
                  and memberMatch.receiverId = :secondUserId)
              or
              (memberMatch.senderId = :secondUserId
                  and memberMatch.receiverId = :firstUserId)
          )
        """)
    Optional<Match> findActiveBetweenForUpdate(
        @Param("firstUserId") Long firstUserId,
        @Param("secondUserId") Long secondUserId);

    @Query(
        """
            select count(memberMatch) > 0 from Match memberMatch
            where (memberMatch.senderId = :senderMemberId and memberMatch.receiverId = :receiverMemberId)
               or (memberMatch.senderId = :receiverMemberId and memberMatch.receiverId = :senderMemberId)
            """
    )
    boolean existsBetween(
        @Param("senderMemberId") Long senderMemberId,
        @Param("receiverMemberId") Long receiverMemberId);
}
