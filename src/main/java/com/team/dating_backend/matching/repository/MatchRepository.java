package com.team.dating_backend.matching.repository;

import com.team.dating_backend.matching.entity.Match;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MatchRepository extends JpaRepository<Match, Long> {

    @Query("""
        select count(memberMatch) > 0 from Match memberMatch
        where (memberMatch.senderId = :senderMemberId and memberMatch.receiverId = :receiverMemberId)
           or (memberMatch.senderId = :receiverMemberId and memberMatch.receiverId = :senderMemberId)
        """)
    boolean existsBetween(
        @Param("senderMemberId") Long senderMemberId,
        @Param("receiverMemberId") Long receiverMemberId);
}
