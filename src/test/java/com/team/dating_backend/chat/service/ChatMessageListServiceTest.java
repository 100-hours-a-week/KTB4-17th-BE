package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.enums.ChatMessageStatus;
import com.team.dating_backend.chat.enums.ChatMessageType;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.chat.repository.ChatParticipantRepository;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

class ChatMessageListServiceTest {

    private static final Long CHAT_ROOM_ID = 30L;
    private static final Long VIEWER_USER_ID = 1L;
    private static final Long VIEWER_PARTICIPANT_ID = 10L;
    private static final Long OTHER_PARTICIPANT_ID = 20L;
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 9, 27, 10, 0);

    private ChatRoomRepository chatRoomRepository;
    private ChatParticipantRepository chatParticipantRepository;
    private ChatMessageRepository chatMessageRepository;
    private ChatMessageListService service;
    private ChatRoom room;
    private ChatParticipant viewer;
    private ChatParticipant other;

    @BeforeEach
    void setUp() {
        chatRoomRepository = mock(ChatRoomRepository.class);
        chatParticipantRepository = mock(ChatParticipantRepository.class);
        chatMessageRepository = mock(ChatMessageRepository.class);
        service = new ChatMessageListService(
            chatRoomRepository, chatParticipantRepository, chatMessageRepository);

        room = new ChatRoom(70L, BASE_TIME.minusDays(1));
        ReflectionTestUtils.setField(room, "id", CHAT_ROOM_ID);
        viewer = participant(VIEWER_PARTICIPANT_ID, VIEWER_USER_ID);
        other = participant(OTHER_PARTICIPANT_ID, 2L);

        given(chatRoomRepository.findById(CHAT_ROOM_ID)).willReturn(Optional.of(room));
        given(chatParticipantRepository.findAllByChatRoomId(CHAT_ROOM_ID))
            .willReturn(List.of(viewer, other));
    }

    @Test
    void 첫_페이지는_요청_크기보다_하나_더_조회하고_화면에는_시간순으로_반환한다() {
        given(chatMessageRepository.findByChatRoomIdAndStatusAndMessageTypeOrderByIdDesc(
            CHAT_ROOM_ID, ChatMessageStatus.SENT, ChatMessageType.TEXT, PageRequest.of(0, 3)))
            .willReturn(List.of(message(30L, OTHER_PARTICIPANT_ID, "최신"),
                message(20L, VIEWER_PARTICIPANT_ID, "중간"),
                message(10L, OTHER_PARTICIPANT_ID, "추가 조회")));

        ChatMessageListService.MessagePage result = service.listMessages(CHAT_ROOM_ID, VIEWER_USER_ID, null, 2);

        assertThat(result.messages()).extracting(ChatMessageListService.MessageItem::messageId)
            .containsExactly(20L, 30L);
        assertThat(result.messages()).extracting(ChatMessageListService.MessageItem::textContent)
            .containsExactly("중간", "최신");
        assertThat(result.hasNext()).isTrue();
        assertThat(result.nextCursor()).isEqualTo(20L);
        verify(chatMessageRepository).findByChatRoomIdAndStatusAndMessageTypeOrderByIdDesc(
            CHAT_ROOM_ID, ChatMessageStatus.SENT, ChatMessageType.TEXT, PageRequest.of(0, 3));
    }

    @Test
    void 과거_페이지는_커서보다_작은_ID만_요청한다() {
        given(chatMessageRepository
            .findByChatRoomIdAndStatusAndMessageTypeAndIdLessThanOrderByIdDesc(
                CHAT_ROOM_ID, ChatMessageStatus.SENT, ChatMessageType.TEXT,
                20L, PageRequest.of(0, 3)))
            .willReturn(List.of(message(19L, OTHER_PARTICIPANT_ID, "이전 메시지")));

        ChatMessageListService.MessagePage result = service.listMessages(CHAT_ROOM_ID, VIEWER_USER_ID, 20L, 2);

        assertThat(result.messages()).extracting(ChatMessageListService.MessageItem::messageId)
            .containsExactly(19L);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.nextCursor()).isNull();
        verify(chatMessageRepository)
            .findByChatRoomIdAndStatusAndMessageTypeAndIdLessThanOrderByIdDesc(
                CHAT_ROOM_ID, ChatMessageStatus.SENT, ChatMessageType.TEXT,
                20L, PageRequest.of(0, 3));
    }

    @Test
    void 현재_사용자가_삭제한_메시지는_삭제_문구로_반환한다() {
        ChatMessage deletedMessage = message(10L, VIEWER_PARTICIPANT_ID, "원문");
        ReflectionTestUtils.setField(deletedMessage, "senderDeletedAt", BASE_TIME);
        given(chatMessageRepository.findByChatRoomIdAndStatusAndMessageTypeOrderByIdDesc(
            CHAT_ROOM_ID, ChatMessageStatus.SENT, ChatMessageType.TEXT, PageRequest.of(0, 2)))
            .willReturn(List.of(deletedMessage));

        ChatMessageListService.MessagePage result = service.listMessages(CHAT_ROOM_ID, VIEWER_USER_ID, null, 1);

        assertThat(result.messages().getFirst().textContent()).isEqualTo("삭제된 메시지입니다.");
    }

    @Test
    void 허용되지_않는_페이지_크기는_메시지를_조회하기_전에_거부한다() {
        assertThatThrownBy(() -> service.listMessages(CHAT_ROOM_ID, VIEWER_USER_ID, null, 101))
            .isInstanceOf(ChatBusinessException.class)
            .satisfies(exception -> assertThat(
                ((ChatBusinessException) exception).getErrorCode())
                .isEqualTo(ChatErrorCode.INVALID_CHAT_MESSAGE_PAGE_SIZE));

        verifyNoInteractions(chatRoomRepository, chatParticipantRepository, chatMessageRepository);
    }

    @Test
    void 채팅방_참여자가_아니면_메시지를_조회할_수_없다() {
        assertThatThrownBy(() -> service.listMessages(CHAT_ROOM_ID, 99L, null, 20))
            .isInstanceOf(ChatBusinessException.class)
            .satisfies(exception -> assertThat(
                ((ChatBusinessException) exception).getErrorCode())
                .isEqualTo(ChatErrorCode.CHAT_ACCESS_DENIED));

        verifyNoInteractions(chatMessageRepository);
    }

    private ChatParticipant participant(Long participantId, Long userId) {
        ChatParticipant participant = new ChatParticipant(CHAT_ROOM_ID, userId);
        ReflectionTestUtils.setField(participant, "id", participantId);
        return participant;
    }

    private ChatMessage message(Long id, Long senderParticipantId, String content) {
        ChatMessage message = new ChatMessage(
            CHAT_ROOM_ID, senderParticipantId, UUID.randomUUID(), content, BASE_TIME);
        ReflectionTestUtils.setField(message, "id", id);
        return message;
    }
}
