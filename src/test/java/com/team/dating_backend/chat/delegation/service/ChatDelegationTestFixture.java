package com.team.dating_backend.chat.delegation.service;

import com.team.dating_backend.chat.delegation.entity.ChatDelegation;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationEndType;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.matching.entity.Match;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.test.util.ReflectionTestUtils;

final class ChatDelegationTestFixture {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 12, 0);

    private ChatDelegationTestFixture() {
    }

    static Fixture inProgress() {
        Match userMatch = new Match(1L, 2L, NOW.minusDays(1));
        ReflectionTestUtils.setField(userMatch, "id", 5L);
        ChatRoom room = new ChatRoom(userMatch, NOW.minusDays(1));
        ReflectionTestUtils.setField(room, "id", 10L);
        ChatParticipant requester = room.addParticipant(1L);
        ReflectionTestUtils.setField(requester, "id", 11L);
        ChatParticipant target = room.addParticipant(2L);
        ReflectionTestUtils.setField(target, "id", 12L);
        UUID requestId = UUID.randomUUID();
        ChatDelegation delegation = new ChatDelegation(
            room, requester, ChatDelegationEndType.GENTLE, requestId, 100L);
        ReflectionTestUtils.setField(delegation, "id", 20L);
        delegation.completeDraft(requestId, "a".repeat(32));
        delegation.start("a".repeat(32), NOW.minusHours(2), NOW.minusMinutes(1));
        return new Fixture(userMatch, room, requester, target, delegation);
    }

    record Fixture(
        Match userMatch,
        ChatRoom room,
        ChatParticipant requester,
        ChatParticipant target,
        ChatDelegation delegation) {}
}
