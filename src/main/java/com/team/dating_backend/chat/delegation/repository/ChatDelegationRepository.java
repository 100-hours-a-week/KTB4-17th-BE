package com.team.dating_backend.chat.delegation.repository;

import com.team.dating_backend.chat.delegation.entity.ChatDelegation;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatDelegationRepository extends JpaRepository<ChatDelegation, Long> {

    Optional<ChatDelegation> findByChatRoomId(Long chatRoomId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select delegation
        from ChatDelegation delegation
        where delegation.chatRoom.id = :chatRoomId
        """)
    Optional<ChatDelegation> findWithLockByChatRoomId(
        @Param("chatRoomId") Long chatRoomId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select delegation from ChatDelegation delegation where delegation.id = :id")
    Optional<ChatDelegation> findWithLockById(@Param("id") Long id);

    @Query("""
        select delegation.id
        from ChatDelegation delegation
        where delegation.status = :status
          and delegation.nextActionAt <= :now
        order by delegation.nextActionAt, delegation.id
        """)
    List<Long> findDueIds(
        @Param("status") ChatDelegationStatus status,
        @Param("now") LocalDateTime now,
        Limit limit);
}
