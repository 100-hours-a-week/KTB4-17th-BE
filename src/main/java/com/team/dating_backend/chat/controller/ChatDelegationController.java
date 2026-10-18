package com.team.dating_backend.chat.controller;

import com.team.dating_backend.chat.dto.request.ChatDelegationCreateRequest;
import com.team.dating_backend.chat.dto.response.ChatDelegationDraftResponse;
import com.team.dating_backend.chat.service.ChatDelegationDraftService;
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
@RequestMapping("/api/v1/chat-rooms/{chatRoomId}/delegations")
public class ChatDelegationController {

    private final ChatDelegationDraftService draftService;

    @PostMapping
    public ResponseEntity<SuccessResponse<ChatDelegationDraftResponse>> createDraft(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long chatRoomId,
        @Valid @RequestBody ChatDelegationCreateRequest request) {
        ChatDelegationDraftResponse response = draftService.create(
            chatRoomId, principal.userId(), request.endType());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(SuccessResponse.of("chat_delegation_draft_created", response));
    }
}
