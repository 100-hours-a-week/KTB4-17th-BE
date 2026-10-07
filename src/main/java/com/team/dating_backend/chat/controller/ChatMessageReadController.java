package com.team.dating_backend.chat.controller;

import com.team.dating_backend.chat.dto.request.ChatMessageReadRequest;
import com.team.dating_backend.chat.dto.response.ChatMessageReadResponse;
import com.team.dating_backend.chat.service.ChatMessageReadService;
import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/chat-rooms/{chatRoomId}/read")
public class ChatMessageReadController {

    private final ChatMessageReadService chatMessageReadService;

    @PostMapping
    public SuccessResponse<ChatMessageReadResponse> markAsRead(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long chatRoomId,
        @Valid @RequestBody ChatMessageReadRequest request) {
        ChatMessageReadResponse response = chatMessageReadService.markAsRead(
            chatRoomId, principal.userId(), request);
        return SuccessResponse.of("chat_message_read_success", response);
    }
}
