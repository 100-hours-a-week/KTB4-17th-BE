package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.chat.dto.read.ChatRoomCursor;
import com.team.dating_backend.chat.dto.read.ChatRoomPage;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.enums.ChatRoomPreviewType;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.repository.ChatRoomListRow;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;

class ChatRoomListServiceTest {

    private static final Long VIEWER_USER_ID = 1L;
    private static final Long VIEWER_PARTICIPANT_ID = 10L;
    private static final Long OTHER_PARTICIPANT_ID = 20L;
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 9, 26, 12, 0);

    private ChatRoomRepository chatRoomRepository;
    private ChatRoomListService service;

    @BeforeEach
    void setUp() {
        chatRoomRepository = org.mockito.Mockito.mock(ChatRoomRepository.class);
        service = new ChatRoomListService(chatRoomRepository);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101})
    void 페이지_크기가_허용_범위를_벗어나면_오류_코드를_반환한다(int size) {
        assertThatThrownBy(() -> service.listRooms(VIEWER_USER_ID, null, size))
            .isInstanceOf(ChatBusinessException.class)
            .satisfies(exception -> assertThat(((ChatBusinessException) exception).getErrorCode())
                .isEqualTo(ChatErrorCode.INVALID_CHAT_ROOM_PAGE_SIZE));
    }

    @Test
    void 다음_페이지_존재_여부를_위해_요청_크기보다_하나_더_조회한다() {
        ChatRoomCursor cursor = new ChatRoomCursor(BASE_TIME, 30L);
        given(chatRoomRepository.findVisibleRoomList(
            VIEWER_USER_ID,
            cursor.activityAt(),
            cursor.chatRoomId(),
            PageRequest.of(0, 21)))
            .willReturn(List.of());

        service.listRooms(VIEWER_USER_ID, cursor, 20);

        verify(chatRoomRepository).findVisibleRoomList(
            VIEWER_USER_ID,
            cursor.activityAt(),
            cursor.chatRoomId(),
            PageRequest.of(0, 21));
    }

    @Test
    void 조회_결과가_요청_크기보다_많으면_요청_크기만_반환하고_다음_커서를_생성한다() {
        ChatRoomListRow first = textRow(30L, BASE_TIME.plusMinutes(2), "첫 번째");
        ChatRoomListRow second = textRow(20L, BASE_TIME.plusMinutes(1), "두 번째");
        ChatRoomListRow extra = textRow(10L, BASE_TIME, "추가 조회");
        given(chatRoomRepository.findVisibleRoomList(
            VIEWER_USER_ID, null, null, PageRequest.of(0, 3)))
            .willReturn(List.of(first, second, extra));

        ChatRoomPage result = service.listRooms(VIEWER_USER_ID, null, 2);

        assertThat(result.items()).hasSize(2);
        assertThat(result.items()).extracting(item -> item.chatRoomId())
            .containsExactly(30L, 20L);
        assertThat(result.pageInfo().hasNext()).isTrue();
        assertThat(result.pageInfo().nextCursor())
            .isEqualTo(new ChatRoomCursor(second.activityAt(), second.chatRoomId()));
    }

    @Test
    void 조회_결과가_요청_크기_이하면_다음_커서가_없다() {
        ChatRoomListRow row = textRow(10L, BASE_TIME, "마지막 메시지");
        given(chatRoomRepository.findVisibleRoomList(
            VIEWER_USER_ID, null, null, PageRequest.of(0, 3)))
            .willReturn(List.of(row));

        ChatRoomPage result = service.listRooms(VIEWER_USER_ID, null, 2);

        assertThat(result.items()).hasSize(1);
        assertThat(result.pageInfo().hasNext()).isFalse();
        assertThat(result.pageInfo().nextCursor()).isNull();
    }

    @Test
    void 활성_방에_메시지가_없으면_대화_시작_문구를_반환한다() {
        ChatRoomPage result = listSingle(emptyRow(ChatRoomStatus.ACTIVE));

        assertThat(result.items().getFirst().preview().type())
            .isEqualTo(ChatRoomPreviewType.EMPTY);
        assertThat(result.items().getFirst().preview().text())
            .isEqualTo("대화를 시작해보세요");
    }

    @Test
    void 종료된_방에_메시지가_없으면_대화_종료_문구를_반환한다() {
        ChatRoomPage result = listSingle(emptyRow(ChatRoomStatus.ENDED));

        assertThat(result.items().getFirst().preview().type())
            .isEqualTo(ChatRoomPreviewType.EMPTY);
        assertThat(result.items().getFirst().preview().text())
            .isEqualTo("대화가 종료되었습니다.");
    }

    @Test
    void 삭제되지_않은_메시지는_텍스트를_그대로_반환한다() {
        ChatRoomPage result = listSingle(textRow(10L, BASE_TIME, "안녕하세요"));

        assertThat(result.items().getFirst().preview().type())
            .isEqualTo(ChatRoomPreviewType.TEXT);
        assertThat(result.items().getFirst().preview().text()).isEqualTo("안녕하세요");
    }

    @Test
    void 발신자인_현재_사용자가_삭제한_메시지는_삭제_문구를_반환한다() {
        ChatRoomListRow row = messageRow(
            VIEWER_PARTICIPANT_ID, BASE_TIME, BASE_TIME.plusMinutes(1), null);

        ChatRoomPage result = listSingle(row);

        assertDeletedPreview(result);
    }

    @Test
    void 수신자인_현재_사용자가_삭제한_메시지는_삭제_문구를_반환한다() {
        ChatRoomListRow row = messageRow(
            OTHER_PARTICIPANT_ID, BASE_TIME, null, BASE_TIME.plusMinutes(1));

        ChatRoomPage result = listSingle(row);

        assertDeletedPreview(result);
    }

    @Test
    void 상대방에게만_삭제된_메시지는_현재_사용자에게_원문을_반환한다() {
        ChatRoomListRow sentByViewer = messageRow(
            VIEWER_PARTICIPANT_ID, BASE_TIME, null, BASE_TIME.plusMinutes(1));
        ChatRoomListRow receivedByViewer = messageRow(
            OTHER_PARTICIPANT_ID, BASE_TIME.minusMinutes(1), BASE_TIME, null);
        given(chatRoomRepository.findVisibleRoomList(
            VIEWER_USER_ID, null, null, PageRequest.of(0, 3)))
            .willReturn(List.of(sentByViewer, receivedByViewer));

        ChatRoomPage result = service.listRooms(VIEWER_USER_ID, null, 2);

        assertThat(result.items()).extracting(item -> item.preview().type())
            .containsExactly(ChatRoomPreviewType.TEXT, ChatRoomPreviewType.TEXT);
        assertThat(result.items()).extracting(item -> item.preview().text())
            .containsExactly("원문", "원문");
    }

    private ChatRoomPage listSingle(ChatRoomListRow row) {
        given(chatRoomRepository.findVisibleRoomList(
            VIEWER_USER_ID, null, null, PageRequest.of(0, 2)))
            .willReturn(List.of(row));
        return service.listRooms(VIEWER_USER_ID, null, 1);
    }

    private ChatRoomListRow emptyRow(ChatRoomStatus status) {
        return new ChatRoomListRow(
            10L,
            VIEWER_PARTICIPANT_ID,
            true,
            2L,
            status,
            null,
            null,
            null,
            null,
            null,
            BASE_TIME);
    }

    private ChatRoomListRow textRow(Long roomId, LocalDateTime activityAt, String text) {
        return new ChatRoomListRow(
            roomId,
            VIEWER_PARTICIPANT_ID,
            true,
            2L,
            ChatRoomStatus.ACTIVE,
            100L,
            OTHER_PARTICIPANT_ID,
            text,
            null,
            null,
            activityAt);
    }

    private ChatRoomListRow messageRow(
        Long senderParticipantId,
        LocalDateTime activityAt,
        LocalDateTime senderDeletedAt,
        LocalDateTime receiverDeletedAt) {
        return new ChatRoomListRow(
            10L,
            VIEWER_PARTICIPANT_ID,
            true,
            2L,
            ChatRoomStatus.ACTIVE,
            100L,
            senderParticipantId,
            "원문",
            senderDeletedAt,
            receiverDeletedAt,
            activityAt);
    }

    private void assertDeletedPreview(ChatRoomPage result) {
        assertThat(result.items().getFirst().preview().type())
            .isEqualTo(ChatRoomPreviewType.DELETED);
        assertThat(result.items().getFirst().preview().text())
            .isEqualTo("삭제된 메시지입니다.");
    }
}
