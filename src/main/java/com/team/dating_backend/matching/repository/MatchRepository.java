package com.team.dating_backend.matching.repository;

import com.team.dating_backend.matching.entity.Match;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MatchRepository extends JpaRepository<Match, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select userMatch from Match userMatch
        where userMatch.status = com.team.dating_backend.matching.enums.MatchStatus.ACTIVE
          and (
              (userMatch.senderId = :firstUserId
                  and userMatch.receiverId = :secondUserId)
              or
              (userMatch.senderId = :secondUserId
                  and userMatch.receiverId = :firstUserId)
          )
        """)
    Optional<Match> findActiveBetweenForUpdate(
        @Param("firstUserId") Long firstUserId,
        @Param("secondUserId") Long secondUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select memberMatch from Match memberMatch
        where memberMatch.status = com.team.dating_backend.matching.enums.MatchStatus.ACTIVE
          and (memberMatch.senderId = :userId or memberMatch.receiverId = :userId)
        order by memberMatch.id
        """)
    List<Match> findAllActiveForUserForUpdate(@Param("userId") Long userId);

    @Query(
        """
            select count(userMatch) > 0 from Match userMatch
            where (userMatch.senderId = :senderUserId and userMatch.receiverId = :receiverUserId)
               or (userMatch.senderId = :receiverUserId and userMatch.receiverId = :senderUserId)
            """
    )
    boolean existsBetween(
        @Param("senderUserId") Long senderUserId,
        @Param("receiverUserId") Long receiverUserId);
}
