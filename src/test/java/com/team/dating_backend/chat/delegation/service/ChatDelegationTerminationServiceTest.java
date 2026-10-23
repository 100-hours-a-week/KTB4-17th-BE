package com.team.dating_backend.chat.delegation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.team.dating_backend.chat.delegation.entity.ChatDelegation;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationLockTarget;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationRepository;
import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatMessageStatus;
import com.team.dating_backend.chat.enums.ChatRoomEndReason;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import com.team.dating_backend.matching.entity.Match;
import com.team.dating_backend.matching.enums.MatchEndReason;
import com.team.dating_backend.matching.enums.MatchStatus;
import com.team.dating_backend.matching.repository.MatchRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

class ChatDelegationTerminationServiceTest {

    private static final LocalDateTime NOW = ChatDelegationTestFixture.NOW;

    private ChatDelegationRepository delegationRepository;
    private ChatRoomRepository chatRoomRepository;
    private ChatMessageRepository chatMessageRepository;
    private MatchRepository matchRepository;
    private ChatDelegationTerminationService service;

    @BeforeEach
    void setUp() {
        delegationRepository = mock(ChatDelegationRepository.class);
        chatRoomRepository = mock(ChatRoomRepository.class);
        chatMessageRepository = mock(ChatMessageRepository.class);
        matchRepository = mock(MatchRepository.class);
        service = new ChatDelegationTerminationService(
            delegationRepository, chatRoomRepository,
            chatMessageRepository, matchRepository);
    }

    @Test
    void 상대방이_24시간_재동안_응답하지_않으면_위임과_대화를_종료한다() {
        ChatDelegationTestFixture.Fixture fixture = ChatDelegationTestFixture.inProgress();
        givenLocks(fixture);
        givenLatestMessage(fixture, fixture.requester(), NOW.minusHours(24));

        assertThat(service.completeIfNoResponse(20L, NOW)).isTrue();

        assertThat(fixture.delegation().getStatus()).isEqualTo(ChatDelegationStatus.COMPLETED);
        assertThat(fixture.room().getStatus()).isEqualTo(ChatRoomStatus.ENDED);
        assertThat(fixture.room().getEndReason())
            .isEqualTo(ChatRoomEndReason.AI_DELEGATION_COMPLETED);
        assertThat(fixture.userMatch().getStatus()).isEqualTo(MatchStatus.ENDED);
        assertThat(fixture.userMatch().getEndReason())
            .isEqualTo(MatchEndReason.AI_DELEGATION_COMPLETED);
    }

    private void givenLocks(ChatDelegationTestFixture.Fixture fixture) {
        when(delegationRepository.findLockTargetById(20L))
            .thenReturn(Optional.of(new ChatDelegationLockTarget(10L, 5L)));
        when(matchRepository.findByIdForUpdate(5L))
            .thenReturn(Optional.of(fixture.userMatch()));
        when(chatRoomRepository.findWithLockById(10L))
            .thenReturn(Optional.of(fixture.room()));
        when(delegationRepository.findWithLockById(20L))
            .thenReturn(Optional.of(fixture.delegation()));
    }

    private void givenLatestMessage(
        ChatDelegationTestFixture.Fixture fixture,
        ChatParticipant sender,
        LocalDateTime sentAt) {
        ChatMessage message = new ChatMessage(
            fixture.room(), sender, UUID.randomUUID(), "마지막 메시지", sentAt);
        when(chatMessageRepository.findRecentByChatRoomIdAndStatus(
            10L, ChatMessageStatus.SENT, Limit.of(1)))
            .thenReturn(List.of(message));
    }

}
