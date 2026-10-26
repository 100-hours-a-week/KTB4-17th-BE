package com.team.dating_backend.chat.delegation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.team.dating_backend.chat.delegation.entity.ChatDelegation;
import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationEndType;
import com.team.dating_backend.chat.delegation.enums.ChatDelegationStatus;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.enums.ChatMessageStatus;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.delegation.repository.ChatDelegationRepository;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import com.team.dating_backend.matching.entity.Match;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;

class ChatDelegationStartPersistenceServiceTest {

    private static final String DRAFT_ID = "a".repeat(32);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 12, 0);
    private ChatRoomRepository roomRepository;
    private ChatMessageRepository messageRepository;
    private ChatDelegationRepository delegationRepository;
    private ChatDelegationSchedulePolicy schedulePolicy;
    private ChatDelegationStartPersistenceService service;
    private ChatRoom room;
    private ChatParticipant requester;
    private ChatParticipant target;
    private ChatDelegation delegation;

    @BeforeEach
    void setUp() {
        roomRepository = mock(ChatRoomRepository.class);
        messageRepository = mock(ChatMessageRepository.class);
        delegationRepository = mock(ChatDelegationRepository.class);
        schedulePolicy = mock(ChatDelegationSchedulePolicy.class);
        service = new ChatDelegationStartPersistenceService(
            roomRepository, messageRepository, delegationRepository, schedulePolicy);
        room = room();
        requester = participant(11L, 1L);
        target = participant(12L, 2L);
        delegation = delegation(requester);
        when(roomRepository.findWithLockById(10L)).thenReturn(Optional.of(room));
        when(delegationRepository.findWithLockById(20L)).thenReturn(Optional.of(delegation));
    }

    @Test
    void AI가_확정한_초안으로_위임을_시작한다() {
        ChatMessage latest = message(target, NOW.minusHours(1));
        LocalDateTime nextActionAt = NOW.plusHours(2);
        when(messageRepository.findRecentByChatRoomIdAndStatus(
            10L, ChatMessageStatus.SENT, Limit.of(1))).thenReturn(List.of(latest));
        when(schedulePolicy.nextActionAt(latest, requester.getId(), NOW))
            .thenReturn(nextActionAt);

        var confirmation = service.prepare(10L, 20L, 1L, DRAFT_ID);
        var response = service.start(10L, 1L, confirmation, NOW);

        assertThat(response.status()).isEqualTo(ChatDelegationStatus.IN_PROGRESS);
        assertThat(delegation.getApprovedAt()).isEqualTo(NOW);
        assertThat(delegation.getNextActionAt()).isEqualTo(nextActionAt);
    }

    @Test
    void 진행_중인_위임을_다시_시작해도_예약_시각을_유지한다() {
        ChatMessage latest = message(requester, NOW.minusHours(1));
        LocalDateTime originalNextActionAt = NOW.plusHours(23);
        when(messageRepository.findRecentByChatRoomIdAndStatus(
            10L, ChatMessageStatus.SENT, Limit.of(1))).thenReturn(List.of(latest));
        when(schedulePolicy.nextActionAt(latest, requester.getId(), NOW))
            .thenReturn(originalNextActionAt);
        service.start(10L, 1L, service.prepare(10L, 20L, 1L, DRAFT_ID), NOW);

        var repeated = service.prepare(10L, 20L, 1L, DRAFT_ID);

        assertThat(repeated.alreadyStarted()).isTrue();
        assertThat(delegation.getNextActionAt()).isEqualTo(originalNextActionAt);
        verify(schedulePolicy).nextActionAt(latest, requester.getId(), NOW);
    }

    @Test
    void 이전에_생성된_초안으로는_위임을_시작할_수_없다() {
        assertError(() -> service.prepare(10L, 20L, 1L, "b".repeat(32)),
            ChatErrorCode.CHAT_DELEGATION_DRAFT_MISMATCH);
    }

    @Test
    void 확정_대기_상태인_초안만_위임을_시작할_수_있다() {
        ReflectionTestUtils.setField(delegation, "status", ChatDelegationStatus.DRAFT_GENERATING);

        assertError(() -> service.prepare(10L, 20L, 1L, DRAFT_ID),
            ChatErrorCode.CHAT_DELEGATION_INVALID_STATUS);
    }

    @Test
    void 요청자가_아니면_확정을_거부한다() {
        ChatDelegation otherDelegation = delegation(target);
        when(delegationRepository.findWithLockById(20L)).thenReturn(Optional.of(otherDelegation));

        assertError(() -> service.prepare(10L, 20L, 1L, DRAFT_ID),
            ChatErrorCode.CHAT_ACCESS_DENIED);
        verify(messageRepository, never()).findRecentByChatRoomIdAndStatus(
            10L, ChatMessageStatus.SENT, Limit.of(1));
    }

    private void assertError(Runnable action, ChatErrorCode code) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(ChatBusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    private ChatDelegation delegation(ChatParticipant owner) {
        UUID requestId = UUID.randomUUID();
        ChatDelegation value = new ChatDelegation(
            room, owner, ChatDelegationEndType.GENTLE, requestId, 101L);
        ReflectionTestUtils.setField(value, "id", 20L);
        value.completeDraft(requestId, DRAFT_ID);
        return value;
    }

    private ChatRoom room() {
        Match userMatch = new Match(1L, 2L, NOW.minusDays(1));
        ReflectionTestUtils.setField(userMatch, "id", 5L);
        ChatRoom value = new ChatRoom(userMatch, NOW.minusDays(1));
        ReflectionTestUtils.setField(value, "id", 10L);
        return value;
    }

    private ChatParticipant participant(Long id, Long userId) {
        ChatParticipant value = room.addParticipant(userId);
        ReflectionTestUtils.setField(value, "id", id);
        return value;
    }

    private ChatMessage message(ChatParticipant sender, LocalDateTime sentAt) {
        ChatMessage value = mock(ChatMessage.class);
        when(value.getSenderParticipantId()).thenReturn(sender.getId());
        when(value.getCreatedAt()).thenReturn(sentAt);
        return value;
    }
}
