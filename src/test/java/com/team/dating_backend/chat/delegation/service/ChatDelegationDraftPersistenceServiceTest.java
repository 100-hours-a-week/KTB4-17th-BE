package com.team.dating_backend.chat.delegation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import com.team.dating_backend.chat.enums.ChatMessageType;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
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
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;

class ChatDelegationDraftPersistenceServiceTest {

    private ChatRoomRepository roomRepository;
    private ChatMessageRepository messageRepository;
    private ChatDelegationRepository delegationRepository;
    private ChatDelegationDraftPersistenceService service;
    private ChatRoom room;
    private ChatParticipant requester;
    private ChatParticipant target;

    @BeforeEach
    void setUp() {
        roomRepository = mock(ChatRoomRepository.class);
        messageRepository = mock(ChatMessageRepository.class);
        delegationRepository = mock(ChatDelegationRepository.class);
        service = new ChatDelegationDraftPersistenceService(
            roomRepository, delegationRepository,
            new ChatDelegationConversationService(messageRepository));
        room = room();
        requester = participant(11L, 1L);
        target = participant(12L, 2L);
        when(roomRepository.findWithLockById(10L)).thenReturn(Optional.of(room));
        when(delegationRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            ChatDelegation delegation = invocation.getArgument(0);
            ReflectionTestUtils.setField(delegation, "id", 20L);
            return delegation;
        });
    }

    @Test
    void 최근_대화를_시간순으로_AI_요청에_변환한다() {
        ChatMessage newest = message(102L, target, ChatMessageType.IMAGE, null, 2);
        ChatMessage oldest = message(101L, requester, ChatMessageType.TEXT, "안녕하세요", 1);
        when(messageRepository.findRecentByChatRoomIdAndStatus(
            10L, ChatMessageStatus.SENT, Limit.of(20)))
            .thenReturn(List.of(newest, oldest));

        var result = service.prepare(10L, 1L, ChatDelegationEndType.GENTLE);

        assertThat(result.aiRequest().recentMessages()).extracting("content")
            .containsExactly("안녕하세요", "(사진이 전달되었습니다)");
        assertThat(result.aiRequest().recentMessages()).extracting("speaker")
            .containsExactly(
                com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.Speaker.REQUESTER,
                com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.Speaker.TARGET);
        assertThat(result.delegationId()).isEqualTo(20L);
        ArgumentCaptor<ChatDelegation> captor = ArgumentCaptor.forClass(ChatDelegation.class);
        verify(delegationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getContextMessageId()).isEqualTo(102L);
    }

    @Test
    void 초안_생성_조건을_충족하지_않으면_요청을_거부한다() {
        when(roomRepository.findWithLockById(10L)).thenReturn(Optional.empty());
        assertError(ChatErrorCode.CHAT_ROOM_NOT_FOUND);
        when(roomRepository.findWithLockById(10L)).thenReturn(Optional.of(room));
        ReflectionTestUtils.setField(requester, "status", ChatParticipantStatus.LEFT);
        assertError(ChatErrorCode.CHAT_ACCESS_DENIED);
        ReflectionTestUtils.setField(requester, "status", ChatParticipantStatus.ACTIVE);
        ReflectionTestUtils.setField(room, "status", ChatRoomStatus.ENDED);
        assertError(ChatErrorCode.CHAT_ROOM_NOT_ACTIVE);
        ReflectionTestUtils.setField(room, "status", ChatRoomStatus.ACTIVE);
        when(delegationRepository.findByChatRoomId(10L))
            .thenReturn(Optional.of(completedDelegation(UUID.randomUUID())));
        assertError(ChatErrorCode.CHAT_DELEGATION_ALREADY_EXISTS);
        when(delegationRepository.findByChatRoomId(10L)).thenReturn(Optional.empty());
        when(messageRepository.findRecentByChatRoomIdAndStatus(any(), any(), any()))
            .thenReturn(List.of());
        assertError(ChatErrorCode.CHAT_DELEGATION_CONVERSATION_REQUIRED);
    }

    @Test
    void 최신_대화를_반영해_초안을_재생성한다() {
        UUID previousRequestId = UUID.randomUUID();
        ChatDelegation delegation = completedDelegation(previousRequestId);
        when(delegationRepository.findWithLockById(20L)).thenReturn(Optional.of(delegation));
        ChatMessage newest = message(102L, target, ChatMessageType.TEXT, "최근 답장", 2);
        ChatMessage oldest = message(101L, requester, ChatMessageType.TEXT, "이전 대화", 1);
        when(messageRepository.findRecentByChatRoomIdAndStatus(
            10L, ChatMessageStatus.SENT, Limit.of(20)))
            .thenReturn(List.of(newest, oldest));

        var result = service.prepareRegeneration(
            10L, 20L, 1L, ChatDelegationEndType.DIRECT);

        assertThat(result.requestId()).isNotEqualTo(previousRequestId);
        assertThat(result.aiRequest().recentMessages())
            .extracting("content").containsExactly("이전 대화", "최근 답장");
        assertThat(delegation.getStatus()).isEqualTo(ChatDelegationStatus.DRAFT_GENERATING);
        assertThat(delegation.getEndType()).isEqualTo(ChatDelegationEndType.DIRECT);
        assertThat(delegation.getAiDraftId()).isNull();
        assertThat(delegation.getContextMessageId()).isEqualTo(102L);
    }

    @Test
    void 초안_생성에_실패하면_다시_시도할_수_있다() {
        UUID previousRequestId = UUID.randomUUID();
        ChatDelegation delegation = delegation(previousRequestId);
        when(delegationRepository.findWithLockById(20L)).thenReturn(Optional.of(delegation));
        service.fail(new ChatDelegationDraftPersistenceService.Preparation(
            20L, previousRequestId, mock(
                com.team.dating_backend.chat.delegation.dto.ai.ChatDelegationAiPayloads.DraftRequest.class)));
        assertThat(delegation.getStatus()).isEqualTo(ChatDelegationStatus.DRAFT_FAILED);

        when(delegationRepository.findByChatRoomId(10L)).thenReturn(Optional.of(delegation));
        ChatMessage latest = message(102L, target, ChatMessageType.TEXT, "답장", 2);
        when(messageRepository.findRecentByChatRoomIdAndStatus(any(), any(), any()))
            .thenReturn(List.of(latest));
        var retry = service.prepare(10L, 1L, ChatDelegationEndType.CASUAL);

        assertThat(retry.delegationId()).isEqualTo(20L);
        assertThat(retry.requestId()).isNotEqualTo(previousRequestId);
        assertThat(delegation.getStatus()).isEqualTo(ChatDelegationStatus.DRAFT_GENERATING);

        service.fail(retry);
        var explicitRetry = service.prepareRegeneration(
            10L, 20L, 1L, ChatDelegationEndType.CASUAL);
        assertThat(explicitRetry.delegationId()).isEqualTo(20L);
        assertThat(delegation.getStatus()).isEqualTo(ChatDelegationStatus.DRAFT_GENERATING);
    }

    @Test
    void 요청자가_아니거나_초안_생성_중이면_재생성을_거부한다() {
        ChatDelegation otherRequester = new ChatDelegation(
            room, target, ChatDelegationEndType.GENTLE, UUID.randomUUID(), 101L);
        ReflectionTestUtils.setField(otherRequester, "id", 20L);
        when(delegationRepository.findWithLockById(20L)).thenReturn(Optional.of(otherRequester));
        assertRegenerationError(ChatErrorCode.CHAT_ACCESS_DENIED);

        ChatDelegation generating = delegation(UUID.randomUUID());
        when(delegationRepository.findWithLockById(20L)).thenReturn(Optional.of(generating));
        assertRegenerationError(ChatErrorCode.CHAT_DELEGATION_INVALID_STATUS);
    }

    private void assertError(ChatErrorCode code) {
        assertThatThrownBy(() -> service.prepare(10L, 1L, ChatDelegationEndType.DIRECT))
            .isInstanceOfSatisfying(ChatBusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    private void assertRegenerationError(ChatErrorCode code) {
        assertThatThrownBy(() -> service.prepareRegeneration(
            10L, 20L, 1L, ChatDelegationEndType.DIRECT))
            .isInstanceOfSatisfying(ChatBusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    private ChatDelegation completedDelegation(UUID requestId) {
        ChatDelegation value = delegation(requestId);
        value.completeDraft(requestId, "a".repeat(32));
        return value;
    }

    private ChatDelegation delegation(UUID requestId) {
        ChatDelegation value = new ChatDelegation(
            room, requester, ChatDelegationEndType.GENTLE, requestId, 101L);
        ReflectionTestUtils.setField(value, "id", 20L);
        return value;
    }

    private ChatRoom room() {
        Match userMatch = new Match(1L, 2L, LocalDateTime.of(2026, 10, 18, 9, 0));
        ReflectionTestUtils.setField(userMatch, "id", 5L);
        ChatRoom value = new ChatRoom(userMatch, LocalDateTime.of(2026, 10, 18, 9, 0));
        ReflectionTestUtils.setField(value, "id", 10L);
        return value;
    }

    private ChatParticipant participant(Long id, Long userId) {
        ChatParticipant value = room.addParticipant(userId);
        ReflectionTestUtils.setField(value, "id", id);
        return value;
    }

    private ChatMessage message(Long id, ChatParticipant sender, ChatMessageType type,
        String text, int minute) {
        ChatMessage value = mock(ChatMessage.class);
        when(value.getId()).thenReturn(id);
        when(value.getSenderParticipantId()).thenReturn(sender.getId());
        when(value.getMessageType()).thenReturn(type);
        when(value.getTextContent()).thenReturn(text);
        when(value.getCreatedAt()).thenReturn(LocalDateTime.of(2026, 10, 18, 10, minute));
        return value;
    }
}
