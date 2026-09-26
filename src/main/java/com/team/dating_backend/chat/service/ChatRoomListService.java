package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.dto.read.ChatRoomCursor;
import com.team.dating_backend.chat.dto.read.ChatRoomPage;
import com.team.dating_backend.chat.dto.read.ChatRoomPageInfo;
import com.team.dating_backend.chat.dto.read.ChatRoomPreview;
import com.team.dating_backend.chat.dto.read.ChatRoomSummary;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.enums.ChatRoomPreviewType;
import com.team.dating_backend.chat.enums.ChatRoomStatus;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.repository.ChatRoomListRow;
import com.team.dating_backend.chat.repository.ChatRoomRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatRoomListService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String DELETED_PREVIEW = "삭제된 메시지입니다.";
    private static final String EMPTY_ACTIVE_PREVIEW = "대화를 시작해보세요";
    private static final String EMPTY_ENDED_PREVIEW = "대화가 종료되었습니다.";

    private final ChatRoomRepository chatRoomRepository;

    @Transactional(readOnly = true)
    public ChatRoomPage listRooms(Long viewerUserId, ChatRoomCursor cursor, int size) {
        validatePageSize(size);

        List<ChatRoomListRow> fetched = chatRoomRepository.findVisibleRoomList(
            viewerUserId,
            cursor == null ? null : cursor.activityAt(),
            cursor == null ? null : cursor.chatRoomId(),
            PageRequest.of(0, size + 1));

        boolean hasNext = fetched.size() > size;
        List<ChatRoomListRow> page = fetched.subList(0, Math.min(size, fetched.size()));
        List<ChatRoomSummary> items = page.stream().map(this::toRoomSummary).toList();
        ChatRoomCursor nextCursor = hasNext
            ? new ChatRoomCursor(page.getLast().activityAt(), page.getLast().chatRoomId())
            : null;

        return new ChatRoomPage(items, new ChatRoomPageInfo(nextCursor, hasNext));
    }

    private void validatePageSize(int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ChatBusinessException(ChatErrorCode.INVALID_CHAT_ROOM_PAGE_SIZE);
        }
    }

    private ChatRoomSummary toRoomSummary(ChatRoomListRow row) {
        return new ChatRoomSummary(
            row.chatRoomId(),
            row.otherUserId(),
            row.chatNotification(),
            toPreview(row),
            row.activityAt());
    }

    private ChatRoomPreview toPreview(ChatRoomListRow row) {
        if (row.lastMessageId() == null) {
            String text = row.roomStatus() == ChatRoomStatus.ACTIVE
                ? EMPTY_ACTIVE_PREVIEW
                : EMPTY_ENDED_PREVIEW;
            return new ChatRoomPreview(ChatRoomPreviewType.EMPTY, text);
        }

        boolean mine = row.viewerParticipantId().equals(row.lastMessageSenderParticipantId());
        boolean deleted = mine
            ? row.lastMessageSenderDeletedAt() != null
            : row.lastMessageReceiverDeletedAt() != null;
        return deleted
            ? new ChatRoomPreview(ChatRoomPreviewType.DELETED, DELETED_PREVIEW)
            : new ChatRoomPreview(ChatRoomPreviewType.TEXT, row.lastMessageTextContent());
    }
}
