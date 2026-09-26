package com.team.dating_backend.chat.controller;

import com.team.dating_backend.chat.dto.read.ChatRoomCursor;
import com.team.dating_backend.chat.dto.read.ChatRoomPage;
import com.team.dating_backend.chat.dto.read.ChatRoomPreview;
import com.team.dating_backend.chat.dto.read.ChatRoomSummary;
import com.team.dating_backend.chat.dto.response.ChatRoomListResponse;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.service.ChatRoomListService;
import com.team.dating_backend.chat.service.ChatRoomParticipantDisplayService;
import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat-rooms")
public class ChatRoomListController {

    private static final String CURSOR_FORMAT_VERSION = "1";
    private static final int MAX_CURSOR_LENGTH = 256;

    private final ChatRoomListService chatRoomListService;
    private final ChatRoomParticipantDisplayService participantDisplayService;

    public ChatRoomListController(
        ChatRoomListService chatRoomListService,
        ChatRoomParticipantDisplayService participantDisplayService) {
        this.chatRoomListService = chatRoomListService;
        this.participantDisplayService = participantDisplayService;
    }

    @GetMapping
    public SuccessResponse<ChatRoomListResponse> getChatRooms(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "20") int size) {
        ChatRoomPage page = chatRoomListService.listRooms(
            principal.userId(), decodeCursor(cursor), size);

        List<Long> otherUserIds = page.items().stream()
            .map(ChatRoomSummary::otherUserId)
            .distinct()
            .toList();
        Map<Long, String> nicknames = participantDisplayService.findNicknames(otherUserIds);

        List<ChatRoomListResponse.Item> items = page.items().stream()
            .map(summary -> toResponseItem(summary, nicknames))
            .toList();
        String nextCursor = page.pageInfo().hasNext()
            ? encodeCursor(page.pageInfo().nextCursor())
            : null;

        return SuccessResponse.of(
            "chat_room_list_success",
            new ChatRoomListResponse(
                items,
                new ChatRoomListResponse.PageInfo(nextCursor, page.pageInfo().hasNext())));
    }

    private ChatRoomListResponse.Item toResponseItem(
        ChatRoomSummary summary,
        Map<Long, String> nicknames) {
        ChatRoomPreview preview = summary.preview();
        return new ChatRoomListResponse.Item(
            summary.chatRoomId(),
            summary.chatNotification(),
            new ChatRoomListResponse.OtherParticipant(
                nicknames.get(summary.otherUserId()), null),
            new ChatRoomListResponse.Preview(preview.type(), preview.text()),
            summary.activityAt());
    }

    private ChatRoomCursor decodeCursor(String token) {
        if (token == null) {
            return null;
        }
        if (token.isBlank() || token.length() > MAX_CURSOR_LENGTH) {
            throw invalidCursor();
        }

        try {
            String payload = new String(
                Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = payload.split("\\|", -1);
            if (parts.length != 3 || !CURSOR_FORMAT_VERSION.equals(parts[0])) {
                throw invalidCursor();
            }

            LocalDateTime activityAt = LocalDateTime.parse(
                parts[1], DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            long chatRoomId = Long.parseLong(parts[2]);
            if (chatRoomId <= 0) {
                throw invalidCursor();
            }
            return new ChatRoomCursor(activityAt, chatRoomId);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw invalidCursor();
        }
    }

    private String encodeCursor(ChatRoomCursor cursor) {
        if (cursor == null) {
            return null;
        }

        String payload = CURSOR_FORMAT_VERSION + "|"
            + cursor.activityAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            + "|" + cursor.chatRoomId();
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private ChatBusinessException invalidCursor() {
        return new ChatBusinessException(ChatErrorCode.INVALID_CHAT_ROOM_CURSOR);
    }
}
