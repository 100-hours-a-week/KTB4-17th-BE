package com.team.dating_backend.chat.repository;

import com.team.dating_backend.chat.entity.ChatParticipant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatParticipantRepository extends JpaRepository<ChatParticipant, Long> {

    List<ChatParticipant> findAllByChatRoomId(Long chatRoomId);
}
