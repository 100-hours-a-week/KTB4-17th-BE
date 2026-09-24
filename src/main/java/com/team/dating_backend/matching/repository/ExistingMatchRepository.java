package com.team.dating_backend.matching.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ExistingMatchRepository {

    private final JdbcTemplate jdbcTemplate;

    public boolean existsBetween(Long senderMemberId, Long receiverMemberId) {
        Long count = jdbcTemplate.queryForObject(
            """
                select count(*) from matches
                where (sender_id = ? and receiver_id = ?)
                   or (sender_id = ? and receiver_id = ?)
                """,
            Long.class,
            senderMemberId,
            receiverMemberId,
            receiverMemberId,
            senderMemberId);
        return count != null && count > 0;
    }
}
