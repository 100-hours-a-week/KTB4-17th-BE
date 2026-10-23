package com.team.dating_backend.chat.delegation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.team.dating_backend.chat.delegation.entity.ChatDelegationMessage;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationMessageStatus;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationMessageRepository;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationRepository;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ChatDelegationParticipantServiceTest {

    private ChatDelegationRepository delegationRepository;
    private ChatDelegationMessageRepository messageRepository;
    private ChatDelegationParticipantService service;

    @BeforeEach
    void setUp() {
        delegationRepository = mock(ChatDelegationRepository.class);
        messageRepository = mock(ChatDelegationMessageRepository.class);
        service = new ChatDelegationParticipantService(
            delegationRepository, messageRepository);
    }

    @ParameterizedTest
    @EnumSource(value = ChatDelegationStatus.class, names = {
        "IN_PROGRESS", "RETRY_REQUIRED", "FAILED"
    })
    void 위임_진행_중에는_요청자가_메시지를_보내거나_나갈_수_없다(
        ChatDelegationStatus status) {
        ChatDelegationTestFixture.Fixture fixture = ChatDelegationTestFixture.inProgress();
        if (status == ChatDelegationStatus.RETRY_REQUIRED) {
            fixture.delegation().requireRetry();
        } else if (status == ChatDelegationStatus.FAILED) {
            fixture.delegation().fail();
        }
        when(delegationRepository.findWithLockByChatRoomId(10L))
            .thenReturn(Optional.of(fixture.delegation()));

        assertActionNotAllowed(() -> service.validateMessageSend(10L, 11L));
        assertActionNotAllowed(() -> service.handleLeave(10L, 11L));
    }

    @Test
    void 상대방이_나가면_위임을_중단하고_남은_답장을_취소한다() {
        ChatDelegationTestFixture.Fixture fixture = ChatDelegationTestFixture.inProgress();
        ChatDelegationMessage message = new ChatDelegationMessage(
            fixture.delegation(), 1, UUID.randomUUID(), 100L);
        message.ready(50L, "전송 전 답장");
        when(delegationRepository.findWithLockByChatRoomId(10L))
            .thenReturn(Optional.of(fixture.delegation()));
        when(messageRepository.findAllWithLockByDelegationIdOrderByTurnDesc(20L))
            .thenReturn(List.of(message));

        service.handleLeave(10L, 12L);

        assertThat(fixture.delegation().getStatus())
            .isEqualTo(ChatDelegationStatus.INTERRUPTED);
        assertThat(fixture.delegation().getNextActionAt()).isNull();
        assertThat(message.getStatus()).isEqualTo(ChatDelegationMessageStatus.CANCELLED);
    }

    private void assertActionNotAllowed(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ChatBusinessException.class)
            .satisfies(exception -> assertThat(
                ((ChatBusinessException) exception).getErrorCode())
                .isEqualTo(ChatErrorCode.CHAT_DELEGATION_ACTION_NOT_ALLOWED));
    }

}
