package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.chat.dto.request.ChatMessageCreateRequest;
import com.team.dating_backend.chat.dto.response.ChatMessageCreateResponse;
import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.entity.ChatMessageOutbox;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.enums.ChatMessageType;
import com.team.dating_backend.chat.enums.ChatParticipantStatus;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.repository.ChatMessageOutboxRepository;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.chat.repository.ChatParticipantRepository;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserBlockRepository;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class ChatMessageSendServiceTest {

    private static final Long CHAT_ROOM_ID = 30L;
    private static final Long SENDER_USER_ID = 1L;
    private static final Long RECEIVER_USER_ID = 2L;
    private static final Long SENDER_PARTICIPANT_ID = 10L;
    private static final Long RECEIVER_PARTICIPANT_ID = 20L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 26, 14, 30);

    private ChatRoomRepository chatRoomRepository;
    private ChatParticipantRepository chatParticipantRepository;
    private ChatMessageRepository chatMessageRepository;
    private ChatMessageOutboxRepository outboxRepository;
    private ChatMessageRateLimiter rateLimiter;
    private UserRepository userRepository;
    private UserBlockRepository userBlockRepository;
    private ChatMessageSendService service;
    private ChatMessageCreateRequest request;

    @BeforeEach
    void setUp() {
        chatRoomRepository = mock(ChatRoomRepository.class);
        chatParticipantRepository = mock(ChatParticipantRepository.class);
        chatMessageRepository = mock(ChatMessageRepository.class);
        outboxRepository = mock(ChatMessageOutboxRepository.class);
        rateLimiter = mock(ChatMessageRateLimiter.class);
        userRepository = mock(UserRepository.class);
        userBlockRepository = mock(UserBlockRepository.class);
        service = new ChatMessageSendService(
            chatRoomRepository,
            chatParticipantRepository,
            chatMessageRepository,
            outboxRepository,
            rateLimiter,
            userRepository,
            userBlockRepository);

        request = new ChatMessageCreateRequest(
            UUID.randomUUID(), ChatMessageType.TEXT, "안녕하세요");
        given(chatRoomRepository.findById(CHAT_ROOM_ID))
            .willReturn(Optional.of(activeRoom()));
        given(chatParticipantRepository.findAllByChatRoomId(CHAT_ROOM_ID))
            .willReturn(List.of(participant(
                SENDER_PARTICIPANT_ID, SENDER_USER_ID, ChatParticipantStatus.ACTIVE),
                participant(RECEIVER_PARTICIPANT_ID, RECEIVER_USER_ID,
                    ChatParticipantStatus.ACTIVE)));
        User senderUser = activeUser();
        User receiverUser = activeUser();
        given(userRepository.findById(SENDER_USER_ID)).willReturn(Optional.of(senderUser));
        given(userRepository.findById(RECEIVER_USER_ID)).willReturn(Optional.of(receiverUser));
        given(userBlockRepository.existsActiveBlockBetween(SENDER_USER_ID, RECEIVER_USER_ID))
            .willReturn(false);
        given(rateLimiter.tryAcquire(SENDER_USER_ID)).willReturn(true);
        given(chatMessageRepository.findBySenderParticipantIdAndClientMessageId(
            SENDER_PARTICIPANT_ID, request.clientMessageId()))
            .willReturn(Optional.empty());
        given(chatMessageRepository.save(any(ChatMessage.class))).willAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            ReflectionTestUtils.setField(message, "id", 500L);
            return message;
        });
    }

    @Test
    void 신규_메시지와_Outbox를_저장하고_생성된_메시지_ID를_응답한다() {
        ChatMessageCreateResponse response = service.sendTextMessage(
            CHAT_ROOM_ID, SENDER_USER_ID, request);

        ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        ArgumentCaptor<ChatMessageOutbox> outboxCaptor = ArgumentCaptor.forClass(ChatMessageOutbox.class);
        verify(chatMessageRepository).save(messageCaptor.capture());
        verify(outboxRepository).save(outboxCaptor.capture());

        ChatMessage savedMessage = messageCaptor.getValue();
        ChatMessageOutbox savedOutbox = outboxCaptor.getValue();
        assertThat(response.messageId()).isEqualTo(500L);
        assertThat(response.createdAt()).isEqualTo(savedMessage.getCreatedAt());
        assertThat(savedMessage.getChatRoomId()).isEqualTo(CHAT_ROOM_ID);
        assertThat(savedMessage.getSenderParticipantId()).isEqualTo(SENDER_PARTICIPANT_ID);
        assertThat(savedMessage.getClientMessageId()).isEqualTo(request.clientMessageId());
        assertThat(savedMessage.getTextContent()).isEqualTo(request.textContent());
        assertThat(savedOutbox.getChatMessageId()).isEqualTo(savedMessage.getId());
        assertThat(savedOutbox.getCreatedAt()).isEqualTo(savedMessage.getCreatedAt());
    }

    @Test
    void 같은_ID와_같은_요청의_재시도는_기존_메시지를_반환하고_다시_저장하지_않는다() {
        ChatMessage existing = new ChatMessage(
            CHAT_ROOM_ID, SENDER_PARTICIPANT_ID, request.clientMessageId(),
            request.textContent(), CREATED_AT.minusMinutes(1));
        ReflectionTestUtils.setField(existing, "id", 499L);
        given(chatMessageRepository.findBySenderParticipantIdAndClientMessageId(
            SENDER_PARTICIPANT_ID, request.clientMessageId()))
            .willReturn(Optional.of(existing));

        ChatMessageCreateResponse response = service.sendTextMessage(
            CHAT_ROOM_ID, SENDER_USER_ID, request);

        assertThat(response.messageId()).isEqualTo(499L);
        assertThat(response.createdAt()).isEqualTo(existing.getCreatedAt());
        verify(chatMessageRepository, never()).save(any(ChatMessage.class));
        verify(outboxRepository, never()).save(any(ChatMessageOutbox.class));
        verify(rateLimiter, never()).tryAcquire(SENDER_USER_ID);
    }

    @Test
    void 같은_ID를_다른_내용으로_재사용하면_충돌_오류를_반환한다() {
        ChatMessage existing = new ChatMessage(
            CHAT_ROOM_ID, SENDER_PARTICIPANT_ID, request.clientMessageId(),
            "다른 내용", CREATED_AT.minusMinutes(1));
        ReflectionTestUtils.setField(existing, "id", 499L);
        given(chatMessageRepository.findBySenderParticipantIdAndClientMessageId(
            SENDER_PARTICIPANT_ID, request.clientMessageId()))
            .willReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.sendTextMessage(CHAT_ROOM_ID, SENDER_USER_ID, request))
            .isInstanceOf(ChatBusinessException.class)
            .satisfies(exception -> assertThat(
                ((ChatBusinessException) exception).getErrorCode())
                .isEqualTo(ChatErrorCode.CLIENT_MESSAGE_ID_CONFLICT));

        verify(chatMessageRepository, never()).save(any(ChatMessage.class));
        verify(outboxRepository, never()).save(any(ChatMessageOutbox.class));
    }

    @Test
    void 요청_제한을_초과하면_메시지와_Outbox를_저장하지_않는다() {
        given(rateLimiter.tryAcquire(SENDER_USER_ID)).willReturn(false);

        assertThatThrownBy(() -> service.sendTextMessage(CHAT_ROOM_ID, SENDER_USER_ID, request))
            .isInstanceOf(ChatBusinessException.class)
            .satisfies(exception -> assertThat(
                ((ChatBusinessException) exception).getErrorCode())
                .isEqualTo(ChatErrorCode.TOO_MANY_MESSAGE_REQUESTS));

        verify(chatMessageRepository, never()).save(any(ChatMessage.class));
        verify(outboxRepository, never()).save(any(ChatMessageOutbox.class));
    }

    @Test
    void 종료된_채팅방에는_메시지를_저장하지_않는다() {
        ChatRoom endedRoom = activeRoom();
        ReflectionTestUtils.setField(endedRoom, "status", ChatRoomStatus.ENDED);
        given(chatRoomRepository.findById(CHAT_ROOM_ID)).willReturn(Optional.of(endedRoom));

        assertThatThrownBy(() -> service.sendTextMessage(CHAT_ROOM_ID, SENDER_USER_ID, request))
            .isInstanceOf(ChatBusinessException.class)
            .satisfies(exception -> assertThat(
                ((ChatBusinessException) exception).getErrorCode())
                .isEqualTo(ChatErrorCode.CHAT_ROOM_NOT_ACTIVE));

        verify(chatMessageRepository, never()).save(any(ChatMessage.class));
        verify(outboxRepository, never()).save(any(ChatMessageOutbox.class));
    }

    private ChatRoom activeRoom() {
        ChatRoom room = new ChatRoom(70L, CREATED_AT);
        ReflectionTestUtils.setField(room, "id", CHAT_ROOM_ID);
        return room;
    }

    private ChatParticipant participant(
        Long participantId, Long userId, ChatParticipantStatus status) {
        ChatParticipant participant = new ChatParticipant(CHAT_ROOM_ID, userId);
        ReflectionTestUtils.setField(participant, "id", participantId);
        ReflectionTestUtils.setField(participant, "status", status);
        return participant;
    }

    private User activeUser() {
        User user = mock(User.class);
        given(user.getStatus()).willReturn(UserStatus.ACTIVE);
        return user;
    }
}
