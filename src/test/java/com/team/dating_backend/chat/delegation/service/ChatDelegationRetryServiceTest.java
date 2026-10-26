package com.team.dating_backend.chat.delegation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.RecentMessage;
import com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.Speaker;
import com.team.dating_backend.chat.delegation.entity.ChatDelegation;
import com.team.dating_backend.chat.delegation.entity.ChatDelegationMessage;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationMessageStatus;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationMessageRepository;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationRepository;
import com.team.dating_backend.chat.delegation.service.ChatDelegationConversationService.MessageContext;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ChatDelegationRetryServiceTest {

    private ChatRoomRepository chatRoomRepository;
    private ChatDelegationRepository delegationRepository;
    private ChatDelegationMessageRepository messageRepository;
    private ChatDelegationConversationService conversationService;
    private ChatDelegationRetryService service;

    @BeforeEach
    void setUp() {
        chatRoomRepository = mock(ChatRoomRepository.class);
        delegationRepository = mock(ChatDelegationRepository.class);
        messageRepository = mock(ChatDelegationMessageRepository.class);
        conversationService = mock(ChatDelegationConversationService.class);
        service = new ChatDelegationRetryService(
            chatRoomRepository, delegationRepository,
            messageRepository, conversationService);
    }

    @Test
    void 실패한_AI_답장을_최신_대화로_재요청한다() {
        Fixture fixture = fixture();
        UUID failedRequestId = fixture.message().getRequestId();
        givenRetryable(fixture);
        when(conversationService.latest(10L, 11L)).thenReturn(
            new MessageContext(105L,
                List.of(new RecentMessage(Speaker.TARGET, "최신 대화"))));

        var response = service.retry(10L, 20L, 1L);

        assertThat(response.status()).isEqualTo(ChatDelegationStatus.IN_PROGRESS);
        assertThat(fixture.delegation().getNextActionAt()).isNull();
        assertThat(fixture.message().getStatus()).isEqualTo(ChatDelegationMessageStatus.RETRY_WAIT);
        assertThat(fixture.message().getRequestId()).isNotEqualTo(failedRequestId);
        assertThat(fixture.message().getContextMessageId()).isEqualTo(105L);
        assertThat(fixture.message().getGenerationFailureCount()).isZero();
        assertThat(fixture.message().getTransportRetryCount()).isZero();
        assertThat(fixture.message().getNextAttemptAt()).isNotNull();
    }

    private void givenRetryable(Fixture fixture) {
        when(chatRoomRepository.findWithLockById(10L))
            .thenReturn(Optional.of(fixture.room()));
        when(delegationRepository.findWithLockById(20L))
            .thenReturn(Optional.of(fixture.delegation()));
        when(messageRepository.findAllWithLockByDelegationIdOrderByTurnDesc(20L))
            .thenReturn(List.of(fixture.message()));
    }

    private Fixture fixture() {
        ChatDelegationTestFixture.Fixture base = ChatDelegationTestFixture.inProgress();
        ChatDelegation delegation = base.delegation();
        delegation.requireRetry();
        ChatDelegationMessage message = new ChatDelegationMessage(
            delegation, 1, UUID.randomUUID(), 100L);
        ReflectionTestUtils.setField(message, "id", 30L);
        message.fail();
        return new Fixture(base.room(), delegation, message);
    }

    private record Fixture(
        com.team.dating_backend.chat.entity.ChatRoom room,
        ChatDelegation delegation, ChatDelegationMessage message) {}
}
