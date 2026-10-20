package com.team.dating_backend.chat.delegation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.team.dating_backend.chat.entity.ChatMessage;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ChatDelegationSchedulePolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 12, 0);

    @Test
    void 요청자가_마지막으로_말한_경우_24시간_동안_상대방_응답을_기다린다() {
        ChatDelegationSchedulePolicy policy = new ChatDelegationSchedulePolicy(bound -> 0);

        LocalDateTime result = policy.nextActionAt(
            message(11L, NOW.minusHours(3)), 11L, NOW);

        assertThat(result).isEqualTo(NOW.plusHours(21));
    }

    @Test
    void 요청자의_마지막_메시지가_24시간_이상_지난_경우_즉시_실행한다() {
        ChatDelegationSchedulePolicy policy = new ChatDelegationSchedulePolicy(bound -> 0);

        LocalDateTime result = policy.nextActionAt(
            message(11L, NOW.minusHours(25)), 11L, NOW);

        assertThat(result).isEqualTo(NOW);
    }

    @Test
    void 상대방이_마지막으로_메시지를_보낸_경우_다음_실행_시각을_계산한다() {
        ChatDelegationSchedulePolicy earliest = new ChatDelegationSchedulePolicy(bound -> 0);
        ChatDelegationSchedulePolicy latest = new ChatDelegationSchedulePolicy(bound -> bound - 1);

        ChatMessage recent = message(12L, NOW.minusHours(1));
        assertThat(earliest.nextActionAt(recent, 11L, NOW)).isEqualTo(NOW.plusHours(1));
        assertThat(latest.nextActionAt(recent, 11L, NOW)).isEqualTo(NOW.plusHours(4));

        ChatMessage fourHoursAgo = message(12L, NOW.minusHours(4));
        assertThat(earliest.nextActionAt(fourHoursAgo, 11L, NOW)).isAfter(NOW);
        assertThat(latest.nextActionAt(fourHoursAgo, 11L, NOW)).isEqualTo(NOW.plusHours(1));

        ChatMessage expired = message(12L, NOW.minusHours(5));
        assertThat(earliest.nextActionAt(expired, 11L, NOW)).isEqualTo(NOW);
    }

    private ChatMessage message(Long senderParticipantId, LocalDateTime sentAt) {
        ChatMessage message = mock(ChatMessage.class);
        when(message.getSenderParticipantId()).thenReturn(senderParticipantId);
        when(message.getCreatedAt()).thenReturn(sentAt);
        return message;
    }
}
