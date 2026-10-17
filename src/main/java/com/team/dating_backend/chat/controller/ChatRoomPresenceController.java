package com.team.dating_backend.chat.controller;

import com.team.dating_backend.chat.dto.request.ChatRoomPresenceRequest;
import com.team.dating_backend.chat.enums.ChatErrorCode;
import com.team.dating_backend.chat.exception.ChatBusinessException;
import com.team.dating_backend.chat.service.ChatRoomPresenceService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import jakarta.validation.Valid;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class ChatRoomPresenceController {

    private final ChatRoomPresenceService presenceService;

    @MessageMapping("/chat-rooms/presence")
    public void updatePresence(
        @Valid @Payload ChatRoomPresenceRequest request,
        Principal principal,
        SimpMessageHeaderAccessor headerAccessor) {
        if (!(principal instanceof Authentication authentication)
            || !authentication.isAuthenticated()
            || !(authentication.getPrincipal() instanceof ServiceAuthenticationPrincipal user)) {
            throw new ChatBusinessException(ChatErrorCode.CHAT_ACCESS_DENIED);
        }

        presenceService.updatePresence(
            user.userId(), headerAccessor.getSessionId(), request,
            headerAccessor.getSessionAttributes());
    }
}
