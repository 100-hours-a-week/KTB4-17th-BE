package com.team.dating_backend.chat.controller;

import com.team.dating_backend.chat.dto.response.ChatRoomLeaveResponse;
import com.team.dating_backend.chat.service.ChatRoomLeaveService;
import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/chat-rooms/{chatRoomId}/leave")
public class ChatRoomLeaveController {

    private final ChatRoomLeaveService chatRoomLeaveService;

    @PostMapping
    public SuccessResponse<ChatRoomLeaveResponse> leave(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long chatRoomId) {
        ChatRoomLeaveResponse response = chatRoomLeaveService.leave(
            chatRoomId, principal.userId());
        return SuccessResponse.of("chat_room_leave_success", response);
    }
}
