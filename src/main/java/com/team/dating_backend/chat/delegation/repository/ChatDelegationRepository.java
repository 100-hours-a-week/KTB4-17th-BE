package com.team.dating_backend.chat.delegation.repository;

import com.team.dating_backend.chat.delegation.entity.ChatDelegation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatDelegationRepository extends JpaRepository<ChatDelegation, Long> {

    Optional<ChatDelegation> findByChatRoomId(Long chatRoomId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select delegation from ChatDelegation delegation where delegation.id = :id")
    Optional<ChatDelegation> findWithLockById(@Param("id") Long id);
}
