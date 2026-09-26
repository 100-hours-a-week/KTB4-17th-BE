package com.team.dating_backend.chat.controller;

import com.team.dating_backend.chat.dto.response.ChatMessageListResponse;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.service.ChatMessageListService;
import com.team.dating_backend.chat.service.ChatMessageListService.MessageItem;
import com.team.dating_backend.chat.service.ChatMessageListService.MessagePage;
import com.team.dating_backend.chat.service.ChatRoomParticipantDisplayService;
import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat-rooms/{chatRoomId}/messages")
public class ChatMessageListController {

    private final ChatMessageListService chatMessageListService;
    private final ChatRoomParticipantDisplayService participantDisplayService;

    public ChatMessageListController(
        ChatMessageListService chatMessageListService,
        ChatRoomParticipantDisplayService participantDisplayService) {
        this.chatMessageListService = chatMessageListService;
        this.participantDisplayService = participantDisplayService;
    }

    @GetMapping
    public SuccessResponse<ChatMessageListResponse> getMessages(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long chatRoomId,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "20") int size) {
        MessagePage page = chatMessageListService.listMessages(
            chatRoomId, principal.userId(), parseCursor(cursor), size);
        Map<Long, String> nicknames = participantDisplayService.findNicknames(
            List.of(page.otherUserId()));

        List<ChatMessageListResponse.Message> messages = page.messages().stream()
            .map(this::toResponseMessage)
            .toList();

        ChatMessageListResponse response = new ChatMessageListResponse(
            new ChatMessageListResponse.ChatRoomInfo(
                page.chatRoomId(),
                page.roomStatus(),
                page.chatNotification(),
                new ChatMessageListResponse.OtherParticipant(
                    page.otherUserId(), nicknames.get(page.otherUserId()), null)),
            messages,
            new ChatMessageListResponse.PageInfo(page.nextCursor(), page.hasNext()));

        return SuccessResponse.of("chat_message_list_success", response);
    }

    private ChatMessageListResponse.Message toResponseMessage(MessageItem message) {
        return new ChatMessageListResponse.Message(
            message.messageId(),
            message.mine(),
            message.messageType(),
            message.textContent(),
            message.status(),
            message.createdAt());
    }

    private Long parseCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        if (cursor.isBlank()) {
            throw new ChatBusinessException(ChatErrorCode.INVALID_CHAT_MESSAGE_CURSOR);
        }

        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException exception) {
            throw new ChatBusinessException(ChatErrorCode.INVALID_CHAT_MESSAGE_CURSOR);
        }
    }
}
