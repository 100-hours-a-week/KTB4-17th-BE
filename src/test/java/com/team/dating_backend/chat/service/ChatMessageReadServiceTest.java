package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.chat.dto.event.ChatMessageReadRequestedEvent;
import com.team.dating_backend.chat.dto.request.ChatMessageReadRequest;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.enums.ChatMessageStatus;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.chat.repository.ChatParticipantRepository;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import com.team.dating_backend.matching.entity.Match;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class ChatMessageReadServiceTest {

    private static final Long ROOM_ID = 30L;
    private static final Long VIEWER_ID = 1L;
    private static final Long OTHER_ID = 2L;
    private static final Long READ_MESSAGE_ID = 500L;

    private ChatRoomRepository chatRoomRepository;
    private ChatParticipantRepository chatParticipantRepository;
    private ChatMessageRepository chatMessageRepository;
    private ApplicationEventPublisher eventPublisher;
    private ChatMessageReadService service;
    private ChatRoom room;
    private ChatParticipant viewer;
    private ChatParticipant other;

    @BeforeEach
    void setUp() {
        chatRoomRepository = mock(ChatRoomRepository.class);
        chatParticipantRepository = mock(ChatParticipantRepository.class);
        chatMessageRepository = mock(ChatMessageRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new ChatMessageReadService(
            chatRoomRepository,
            chatParticipantRepository,
            chatMessageRepository,
            eventPublisher);

        LocalDateTime now = LocalDateTime.of(2026, 9, 27, 10, 0);
        Match match = new Match(VIEWER_ID, OTHER_ID, now.minusDays(1));
        ReflectionTestUtils.setField(match, "id", 70L);
        room = new ChatRoom(match, now.minusDays(1));
        ReflectionTestUtils.setField(room, "id", ROOM_ID);
        viewer = participant(10L, VIEWER_ID);
        other = participant(20L, OTHER_ID);

        given(chatRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
        given(chatParticipantRepository.findByChatRoomAndUserForUpdate(ROOM_ID, VIEWER_ID))
            .willReturn(Optional.of(viewer));
        given(chatMessageRepository.existsByIdAndChatRoomIdAndStatus(
            READ_MESSAGE_ID, ROOM_ID, ChatMessageStatus.SENT)).willReturn(true);
    }

    @Test
    void 읽음_커서를_전진시키고_커밋_후_상대방에게_읽음_이벤트를_요청한다() {
        var response = service.markAsRead(
            ROOM_ID, VIEWER_ID, new ChatMessageReadRequest(READ_MESSAGE_ID));

        assertThat(response.chatRoomId()).isEqualTo(ROOM_ID);
        assertThat(response.lastReadMessageId()).isEqualTo(READ_MESSAGE_ID);
        assertThat(viewer.getLastReadMessageId()).isEqualTo(READ_MESSAGE_ID);
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        ChatMessageReadRequestedEvent event = (ChatMessageReadRequestedEvent) eventCaptor.getValue();
        assertThat(event.recipientUserId()).isEqualTo(OTHER_ID);
        assertThat(event.receipt().chatRoomId()).isEqualTo(ROOM_ID);
        assertThat(event.receipt().readerUserId()).isEqualTo(VIEWER_ID);
        assertThat(event.receipt().lastReadMessageId()).isEqualTo(READ_MESSAGE_ID);
    }

    @Test
    void 오래된_읽음_요청으로_커서를_뒤로_돌리지_않는다() {
        ReflectionTestUtils.setField(viewer, "lastReadMessageId", 700L);
        given(chatMessageRepository.existsByIdAndChatRoomIdAndStatus(
            600L, ROOM_ID, ChatMessageStatus.SENT)).willReturn(true);

        var response = service.markAsRead(
            ROOM_ID, VIEWER_ID, new ChatMessageReadRequest(600L));

        assertThat(response.lastReadMessageId()).isEqualTo(700L);
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        ChatMessageReadRequestedEvent event = (ChatMessageReadRequestedEvent) eventCaptor.getValue();
        assertThat(event.receipt().lastReadMessageId()).isEqualTo(700L);
    }

    @Test
    void 다른_방_또는_저장되지_않은_메시지_ID는_읽음_커서로_받지_않는다() {
        given(chatMessageRepository.existsByIdAndChatRoomIdAndStatus(
            READ_MESSAGE_ID, ROOM_ID, ChatMessageStatus.SENT)).willReturn(false);

        assertThatThrownBy(() -> service.markAsRead(
            ROOM_ID, VIEWER_ID, new ChatMessageReadRequest(READ_MESSAGE_ID)))
            .isInstanceOf(ChatBusinessException.class)
            .satisfies(exception -> assertThat(
                ((ChatBusinessException) exception).getErrorCode())
                .isEqualTo(ChatErrorCode.INVALID_CHAT_MESSAGE_READ_CURSOR));
        assertThat(viewer.getLastReadMessageId()).isNull();
        verifyNoInteractions(eventPublisher);
    }

    private ChatParticipant participant(Long participantId, Long userId) {
        ChatParticipant participant = room.addParticipant(userId);
        ReflectionTestUtils.setField(participant, "id", participantId);
        return participant;
    }
}
