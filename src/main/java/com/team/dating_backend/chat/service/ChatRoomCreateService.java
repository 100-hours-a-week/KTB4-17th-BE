package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.repository.ChatParticipantRepository;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatRoomCreateService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatParticipantRepository chatParticipantRepository;

    @Transactional
    public Long createChatRoom(
        Long matchId, Long senderId, Long receiverId, LocalDateTime createdAt) {
        Optional<ChatRoom> existingChatRoom = chatRoomRepository.findByMatchId(matchId);
        if (existingChatRoom.isPresent()) {
            return existingChatRoom.get().getId();
        }
        ChatRoom chatRoom = chatRoomRepository.save(new ChatRoom(matchId, createdAt));
        chatParticipantRepository.saveAll(List.of(
            new ChatParticipant(chatRoom.getId(), senderId),
            new ChatParticipant(chatRoom.getId(), receiverId)));
        return chatRoom.getId();
    }
}
