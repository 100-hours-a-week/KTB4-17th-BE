package com.team.dating_backend.chat.delegation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.team.dating_backend.chat.delegation.enums.ChatDelegationMessageStatus;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatDelegationMessageTest {

    @Test
    void 생성_실패는_최대_두_번_재시도한_뒤_종료한다() {
        UUID initialRequestId = UUID.randomUUID();
        ChatDelegationMessage message = message(initialRequestId);
        LocalDateTime now = LocalDateTime.of(2026, 10, 21, 10, 0);

        assertThat(message.retryGeneration(now.plusMinutes(1))).isTrue();
        assertThat(message.getRequestId()).isNotEqualTo(initialRequestId);
        assertThat(message.getContextMessageId()).isEqualTo(100L);
        message.resume(100L, now.plusMinutes(1));
        assertThat(message.retryGeneration(now.plusMinutes(3))).isTrue();
        message.resume(100L, now.plusMinutes(3));
        assertThat(message.retryGeneration(now.plusMinutes(5))).isFalse();

        assertThat(message.getGenerationFailureCount()).isEqualTo(3);
        assertThat(message.getStatus()).isEqualTo(ChatDelegationMessageStatus.FAILED);
    }

    @Test
    void 통신_재시도는_동일한_요청과_대화를_유지하고_최대_두_번_재시도한_뒤_종료한다() {
        UUID requestId = UUID.randomUUID();
        ChatDelegationMessage message = message(requestId);
        LocalDateTime now = LocalDateTime.of(2026, 10, 21, 10, 0);

        assertThat(message.retryTransport(now.plusMinutes(1))).isTrue();
        message.resume(100L, now.plusMinutes(1));
        assertThat(message.retryTransport(now.plusMinutes(3))).isTrue();
        message.resume(100L, now.plusMinutes(3));
        assertThat(message.retryTransport(now.plusMinutes(5))).isFalse();

        assertThat(message.getRequestId()).isEqualTo(requestId);
        assertThat(message.getContextMessageId()).isEqualTo(100L);
        assertThat(message.getTransportRetryCount()).isEqualTo(3);
        assertThat(message.getStatus()).isEqualTo(ChatDelegationMessageStatus.FAILED);
    }

    private ChatDelegationMessage message(UUID requestId) {
        return new ChatDelegationMessage(mock(ChatDelegation.class), 1, requestId, 100L);
    }
}
