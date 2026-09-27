package com.team.dating_backend.chat.controller;

import com.team.dating_backend.chat.service.ChatMessageImageAccessService;
import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.file.dto.FileAccessUrlResult;
import com.team.dating_backend.file.dto.response.FileAccessUrlResponse;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/chat-rooms/{chatRoomId}/images/{fileId}/access-url")
public class ChatMessageImageAccessController {

    private final ChatMessageImageAccessService imageAccessService;

    @GetMapping
    public SuccessResponse<FileAccessUrlResponse> createAccessUrl(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long chatRoomId,
        @PathVariable Long fileId) {
        FileAccessUrlResult result = imageAccessService.createAccessUrl(
            chatRoomId, fileId, principal.userId());
        return SuccessResponse.of(
            "chat_message_image_access_url_success", FileAccessUrlResponse.from(result));
    }
}
