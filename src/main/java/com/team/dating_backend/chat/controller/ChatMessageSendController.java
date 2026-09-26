package com.team.dating_backend.chat.controller;

import com.team.dating_backend.chat.dto.request.ChatMessageCreateRequest;
import com.team.dating_backend.chat.dto.response.ChatMessageCreateResponse;
import com.team.dating_backend.chat.service.ChatMessageSendService;
import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/chat-rooms/{chatRoomId}/messages")
public class ChatMessageSendController {

    private final ChatMessageSendService chatMessageSendService;

    @PostMapping
    public ResponseEntity<SuccessResponse<ChatMessageCreateResponse>> sendMessage(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long chatRoomId,
        @Valid @RequestBody ChatMessageCreateRequest request) {
        ChatMessageCreateResponse response = chatMessageSendService.sendTextMessage(
            chatRoomId, principal.userId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(SuccessResponse.of("chat_message_send_success", response));
    }
}
