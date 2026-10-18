package com.team.dating_backend.chat.repository;

import com.team.dating_backend.chat.entity.ChatDelegation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatDelegationRepository extends JpaRepository<ChatDelegation, Long> {

    boolean existsByChatRoomId(Long chatRoomId);
}
