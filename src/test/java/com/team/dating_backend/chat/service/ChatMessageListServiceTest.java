package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.chat.dto.response.ChatMessageListResponse;
import com.team.dating_backend.chat.entity.ChatMessage;
import com.team.dating_backend.chat.entity.ChatParticipant;
import com.team.dating_backend.chat.entity.ChatRoom;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.enums.ChatMessageStatus;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.repository.ChatMessageRepository;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import com.team.dating_backend.matching.entity.Match;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
    private static final Long OTHER_USER_ID = 2L;
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 9, 27, 10, 0);

    private ChatRoomRepository chatRoomRepository;
    private ChatMessageRepository chatMessageRepository;
    private ChatRoomParticipantDisplayService participantDisplayService;
    private ChatMessageListService service;
    private ChatRoom room;
    private ChatParticipant viewer;
    private ChatParticipant other;

    @BeforeEach
    void setUp() {
        chatRoomRepository = mock(ChatRoomRepository.class);
        chatMessageRepository = mock(ChatMessageRepository.class);
        participantDisplayService = mock(ChatRoomParticipantDisplayService.class);
        service = new ChatMessageListService(
            chatRoomRepository, chatMessageRepository, participantDisplayService);

        Match match = new Match(VIEWER_USER_ID, OTHER_USER_ID, BASE_TIME.minusDays(1));
        ReflectionTestUtils.setField(match, "id", 70L);
        room = new ChatRoom(match, BASE_TIME.minusDays(1));
        ReflectionTestUtils.setField(room, "id", CHAT_ROOM_ID);
        viewer = participant(VIEWER_PARTICIPANT_ID, VIEWER_USER_ID);
        other = participant(OTHER_PARTICIPANT_ID, OTHER_USER_ID);

        given(chatRoomRepository.findById(CHAT_ROOM_ID)).willReturn(Optional.of(room));
    }

    @Test
    void 첫_페이지는_요청_크기보다_하나_더_조회하고_화면에는_시간순으로_반환한다() {
        given(chatMessageRepository.findByChatRoomIdAndStatusOrderByIdDesc(
            CHAT_ROOM_ID, ChatMessageStatus.SENT, PageRequest.of(0, 3)))
            .willReturn(List.of(message(30L, OTHER_PARTICIPANT_ID, "최신"),
                message(20L, VIEWER_PARTICIPANT_ID, "중간"),
                message(10L, OTHER_PARTICIPANT_ID, "추가 조회")));

        ChatMessageListResponse result = service.listMessages(CHAT_ROOM_ID,
            VIEWER_USER_ID, null, 2);

        assertThat(result.messages()).extracting(ChatMessageListResponse.Message::messageId)
            .containsExactly(20L, 30L);
        assertThat(result.messages()).extracting(ChatMessageListResponse.Message::textContent)
            .containsExactly("중간", "최신");
        assertThat(result.messages()).extracting(ChatMessageListResponse.Message::unreadCount)
            .containsExactly(1, 0);
        assertThat(result.pageInfo().hasNext()).isTrue();
        assertThat(result.pageInfo().nextCursor()).isEqualTo(20L);
        verify(chatMessageRepository).findByChatRoomIdAndStatusOrderByIdDesc(
            CHAT_ROOM_ID, ChatMessageStatus.SENT, PageRequest.of(0, 3));
    }

    @Test
    void 상대방의_읽음_커서보다_이전인_내_메시지에는_미읽음_표시를_하지_않는다() {
        ReflectionTestUtils.setField(other, "lastReadMessageId", 30L);
        given(chatMessageRepository.findByChatRoomIdAndStatusOrderByIdDesc(
            CHAT_ROOM_ID, ChatMessageStatus.SENT, PageRequest.of(0, 3)))
            .willReturn(List.of(message(30L, OTHER_PARTICIPANT_ID, "받은 메시지"),
                message(20L, VIEWER_PARTICIPANT_ID, "내 메시지")));

        ChatMessageListResponse result = service.listMessages(
            CHAT_ROOM_ID, VIEWER_USER_ID, null, 2);

        assertThat(result.messages()).extracting(ChatMessageListResponse.Message::unreadCount)
            .containsExactly(0, 0);
    }

    @Test
    void 과거_페이지는_커서보다_작은_ID만_요청한다() {
        given(chatMessageRepository
            .findByChatRoomIdAndStatusAndIdLessThanOrderByIdDesc(
                CHAT_ROOM_ID, ChatMessageStatus.SENT,
                20L, PageRequest.of(0, 3)))
            .willReturn(List.of(message(19L, OTHER_PARTICIPANT_ID, "이전 메시지")));

        ChatMessageListResponse result = service.listMessages(CHAT_ROOM_ID,
            VIEWER_USER_ID, 20L, 2);

        assertThat(result.messages()).extracting(ChatMessageListResponse.Message::messageId)
            .containsExactly(19L);
        assertThat(result.pageInfo().hasNext()).isFalse();
        assertThat(result.pageInfo().nextCursor()).isNull();
        verify(chatMessageRepository)
            .findByChatRoomIdAndStatusAndIdLessThanOrderByIdDesc(
                CHAT_ROOM_ID, ChatMessageStatus.SENT,
                20L, PageRequest.of(0, 3));
    }

    @Test
    void 현재_사용자가_삭제한_메시지는_삭제_문구로_반환한다() {
        ChatMessage deletedMessage = message(10L, VIEWER_PARTICIPANT_ID, "원문");
        ReflectionTestUtils.setField(deletedMessage, "senderDeletedAt", BASE_TIME);
        given(chatMessageRepository.findByChatRoomIdAndStatusOrderByIdDesc(
            CHAT_ROOM_ID, ChatMessageStatus.SENT, PageRequest.of(0, 2)))
            .willReturn(List.of(deletedMessage));

        ChatMessageListResponse result = service.listMessages(CHAT_ROOM_ID,
            VIEWER_USER_ID, null, 1);

        assertThat(result.messages().getFirst().textContent()).isEqualTo("삭제된 메시지입니다.");
    }

    @Test
    void 상대방_표시_정보를_조회해_응답에_포함한다() {
        String profileImageUrl = "https://example.com/profile.jpg";
        given(chatMessageRepository.findByChatRoomIdAndStatusOrderByIdDesc(
            CHAT_ROOM_ID, ChatMessageStatus.SENT, PageRequest.of(0, 21)))
            .willReturn(List.of());
        given(participantDisplayService.findNicknames(List.of(OTHER_USER_ID)))
            .willReturn(Map.of(OTHER_USER_ID, "상대방"));
        given(participantDisplayService.findProfileImageUrls(List.of(OTHER_USER_ID)))
            .willReturn(Map.of(OTHER_USER_ID, profileImageUrl));

        ChatMessageListResponse result = service.listMessages(
            CHAT_ROOM_ID, VIEWER_USER_ID, null, 20);

        assertThat(result.chatRoom().otherParticipant())
            .isEqualTo(new ChatMessageListResponse.OtherParticipant(
                OTHER_USER_ID, "상대방", profileImageUrl));
        verify(participantDisplayService).findNicknames(List.of(OTHER_USER_ID));
        verify(participantDisplayService).findProfileImageUrls(List.of(OTHER_USER_ID));
    }

    @Test
    void 허용되지_않는_페이지_크기는_메시지를_조회하기_전에_거부한다() {
        assertThatThrownBy(() -> service.listMessages(CHAT_ROOM_ID, VIEWER_USER_ID, null, 101))
            .isInstanceOf(ChatBusinessException.class)
            .satisfies(exception -> assertThat(
                ((ChatBusinessException) exception).getErrorCode())
                .isEqualTo(ChatErrorCode.INVALID_CHAT_MESSAGE_PAGE_SIZE));

        verifyNoInteractions(chatRoomRepository, chatMessageRepository);
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
        ChatParticipant participant = room.addParticipant(userId);
        ReflectionTestUtils.setField(participant, "id", participantId);
        return participant;
    }

    private ChatMessage message(Long id, Long senderParticipantId, String content) {
        ChatParticipant sender = senderParticipantId.equals(VIEWER_PARTICIPANT_ID)
            ? viewer
            : other;
        ChatMessage message = new ChatMessage(
            room, sender, UUID.randomUUID(), content, BASE_TIME);
        ReflectionTestUtils.setField(message, "id", id);
        return message;
    }
}
