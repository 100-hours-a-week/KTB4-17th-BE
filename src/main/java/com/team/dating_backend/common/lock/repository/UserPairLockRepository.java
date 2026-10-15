package com.team.dating_backend.common.lock.repository;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UserPairLockRepository {

    private final JdbcTemplate jdbcTemplate;

    public void acquire(Long requesterUserId, Long targetUserId) {
        if (requesterUserId.equals(targetUserId)) {
            throw new IllegalArgumentException("User pair IDs must be different");
        }

        Long lowerUserId = Math.min(requesterUserId, targetUserId);
        Long higherUserId = Math.max(requesterUserId, targetUserId);
        jdbcTemplate.update(
            """
                INSERT INTO user_pair_locks (lower_user_id, higher_user_id, created_at)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE lower_user_id = VALUES(lower_user_id)
                """,
            lowerUserId,
            higherUserId,
            LocalDateTime.now());
    }
}
