package com.team.dating_backend.chat.delegation.controller;

import com.team.dating_backend.chat.delegation.dto.request.ChatDelegationConfirmRequest;
import com.team.dating_backend.chat.delegation.dto.request.ChatDelegationCreateRequest;
import com.team.dating_backend.chat.delegation.dto.response.ChatDelegationDraftResponse;
import com.team.dating_backend.chat.delegation.dto.response.ChatDelegationStartResponse;
import com.team.dating_backend.chat.delegation.service.ChatDelegationDraftService;
import com.team.dating_backend.chat.delegation.service.ChatDelegationStartService;
import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "app.chat", name = "runtime-enabled", havingValue = "true")
@RequiredArgsConstructor
@RequestMapping("/api/v1/chat-rooms/{chatRoomId}/delegations")
public class ChatDelegationController {

    private final ChatDelegationDraftService draftService;
    private final ChatDelegationStartService startService;

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

    @PostMapping("/{delegationId}/drafts")
    public ResponseEntity<SuccessResponse<ChatDelegationDraftResponse>> regenerateDraft(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long chatRoomId,
        @PathVariable Long delegationId,
        @Valid @RequestBody ChatDelegationCreateRequest request) {
        ChatDelegationDraftResponse response = draftService.regenerate(
            chatRoomId, delegationId, principal.userId(), request.endType());
        return ResponseEntity.ok(
            SuccessResponse.of("chat_delegation_draft_regenerated", response));
    }

    @PostMapping("/{delegationId}/confirm")
    public ResponseEntity<SuccessResponse<ChatDelegationStartResponse>> confirmDraft(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long chatRoomId,
        @PathVariable Long delegationId,
        @Valid @RequestBody ChatDelegationConfirmRequest request) {
        ChatDelegationStartResponse response = startService.start(
            chatRoomId, delegationId, principal.userId(), request.draftId());
        return ResponseEntity.ok(
            SuccessResponse.of("chat_delegation_started", response));
    }
}
