package com.team.dating_backend.chat.delegation.repository;

import com.team.dating_backend.chat.delegation.entity.ChatDelegationMessage;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationMessageStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatDelegationMessageRepository
    extends
        JpaRepository<ChatDelegationMessage, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select message
        from ChatDelegationMessage message
        where message.id = :id
        """)
    Optional<ChatDelegationMessage> findWithLockById(@Param("id") Long id);

    long countByDelegationId(Long delegationId);

    @Query("""
        select message.id
        from ChatDelegationMessage message
        where message.status = :status
          and message.nextAttemptAt <= :now
        order by message.nextAttemptAt, message.id
        """)
    List<Long> findDueRetryIds(
        @Param("status") ChatDelegationMessageStatus status,
        @Param("now") LocalDateTime now,
        Limit limit);

    @Query("""
        select message.id
        from ChatDelegationMessage message
        where message.status = :status
          and message.updatedAt <= :deadline
        order by message.updatedAt, message.id
        """)
    List<Long> findRecoverableIds(
        @Param("status") ChatDelegationMessageStatus status,
        @Param("deadline") LocalDateTime deadline,
        Limit limit);
}
