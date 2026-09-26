package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.repository.ChatParticipantDisplayRow;
import com.team.dating_backend.chat.repository.ChatRoomParticipantDisplayRepository;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatRoomParticipantDisplayService {

    private final ChatRoomParticipantDisplayRepository participantDisplayRepository;

    @Transactional(readOnly = true)
    public Map<Long, String> findNicknames(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }

        return participantDisplayRepository.findByUserIds(userIds).stream()
            .collect(Collectors.toUnmodifiableMap(
                ChatParticipantDisplayRow::userId,
                ChatParticipantDisplayRow::nickname));
    }
}
