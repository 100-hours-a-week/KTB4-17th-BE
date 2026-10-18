package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.team.dating_backend.chat.entity.ChatDelegation;
import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatDelegationEndType;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.enums.ChatMessageStatus;
import com.team.dating_backend.chat.enums.ChatMessageType;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.repository.ChatDelegationRepository;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import com.team.dating_backend.matching.entity.Match;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
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
            roomRepository, messageRepository, delegationRepository);
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
        when(messageRepository.findByChatRoomIdAndStatusOrderByIdDesc(
            10L, ChatMessageStatus.SENT, PageRequest.of(0, 20)))
            .thenReturn(List.of(newest, oldest));

        var result = service.prepare(10L, 1L, ChatDelegationEndType.GENTLE);

        assertThat(result.aiRequest().recentMessages()).extracting("content")
            .containsExactly("안녕하세요", "(사진이 전달되었습니다)");
        assertThat(result.aiRequest().recentMessages()).extracting("speaker")
            .containsExactly(
                com.team.dating_backend.chat.dto.ai.ChatDelegationAiPayloads.Speaker.REQUESTER,
                com.team.dating_backend.chat.dto.ai.ChatDelegationAiPayloads.Speaker.TARGET);
        assertThat(result.delegationId()).isEqualTo(20L);
        ArgumentCaptor<ChatDelegation> captor = ArgumentCaptor.forClass(ChatDelegation.class);
        verify(delegationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getContextMessageId()).isEqualTo(102L);
    }

    @Test
    void 참여자와_채팅방과_대화와_중복을_검증한다() {
        when(roomRepository.findWithLockById(10L)).thenReturn(Optional.empty());
        assertError(ChatErrorCode.CHAT_ROOM_NOT_FOUND);
        when(roomRepository.findWithLockById(10L)).thenReturn(Optional.of(room));
        ReflectionTestUtils.setField(requester, "status", ChatParticipantStatus.LEFT);
        assertError(ChatErrorCode.CHAT_ACCESS_DENIED);
        ReflectionTestUtils.setField(requester, "status", ChatParticipantStatus.ACTIVE);
        ReflectionTestUtils.setField(room, "status", ChatRoomStatus.ENDED);
        assertError(ChatErrorCode.CHAT_ROOM_NOT_ACTIVE);
        ReflectionTestUtils.setField(room, "status", ChatRoomStatus.ACTIVE);
        when(delegationRepository.existsByChatRoomId(10L)).thenReturn(true);
        assertError(ChatErrorCode.CHAT_DELEGATION_ALREADY_EXISTS);
        when(delegationRepository.existsByChatRoomId(10L)).thenReturn(false);
        when(messageRepository.findByChatRoomIdAndStatusOrderByIdDesc(any(), any(), any()))
            .thenReturn(List.of());
        assertError(ChatErrorCode.CHAT_DELEGATION_CONVERSATION_REQUIRED);
    }

    private void assertError(ChatErrorCode code) {
        assertThatThrownBy(() -> service.prepare(10L, 1L, ChatDelegationEndType.DIRECT))
            .isInstanceOfSatisfying(ChatBusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
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
