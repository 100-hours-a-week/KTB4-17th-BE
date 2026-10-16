package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.enums.ChatRoomEndReason;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatRoomBlockTerminationService {

    private final ChatRoomRepository chatRoomRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void terminateBetween(Long firstUserId, Long secondUserId, LocalDateTime endedAt) {
        chatRoomRepository.findActiveBetweenUsersForUpdate(firstUserId, secondUserId)
            .ifPresent(room -> room.end(ChatRoomEndReason.BLOCKED, endedAt));
    }
}
