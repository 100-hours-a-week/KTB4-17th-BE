package com.team.dating_backend.chat.controller;

import com.team.dating_backend.chat.dto.response.ChatMessageListResponse;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.service.ChatMessageListService;
import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
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

    public ChatMessageListController(ChatMessageListService chatMessageListService) {
        this.chatMessageListService = chatMessageListService;
    }

    @GetMapping
    public SuccessResponse<ChatMessageListResponse> getMessages(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long chatRoomId,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "20") int size) {
        ChatMessageListResponse response = chatMessageListService.listMessages(
            chatRoomId, principal.userId(), parseCursor(cursor), size);
        return SuccessResponse.of("chat_message_list_success", response);
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
