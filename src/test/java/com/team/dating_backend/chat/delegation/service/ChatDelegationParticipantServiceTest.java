package com.team.dating_backend.chat.delegation.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationRepository;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ChatDelegationParticipantServiceTest {

    private ChatDelegationRepository delegationRepository;
    private ChatDelegationParticipantService service;

    @BeforeEach
    void setUp() {
        delegationRepository = mock(ChatDelegationRepository.class);
        service = new ChatDelegationParticipantService(delegationRepository);
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

    private void assertActionNotAllowed(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ChatBusinessException.class)
            .satisfies(exception -> assertThat(
                ((ChatBusinessException) exception).getErrorCode())
                .isEqualTo(ChatErrorCode.CHAT_DELEGATION_ACTION_NOT_ALLOWED));
    }

}
