package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.repository.ChatParticipantRepository;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import com.team.dating_backend.matching.entity.Match;
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
        Match match, LocalDateTime createdAt) {
        Optional<ChatRoom> existingChatRoom = chatRoomRepository.findByMatchId(match.getId());
        if (existingChatRoom.isPresent()) {
            return existingChatRoom.get().getId();
        }
        ChatRoom chatRoom = chatRoomRepository.save(new ChatRoom(match, createdAt));
        List<ChatParticipant> participants = List.of(
            chatRoom.addParticipant(match.getSenderId()),
            chatRoom.addParticipant(match.getReceiverId()));
        chatParticipantRepository.saveAll(participants);
        return chatRoom.getId();
    }
}
