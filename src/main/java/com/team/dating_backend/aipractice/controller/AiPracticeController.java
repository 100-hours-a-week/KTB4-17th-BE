package com.team.dating_backend.aipractice.controller;

import com.team.dating_backend.aipractice.dto.request.AiPracticeChatCreateRequest;
import com.team.dating_backend.aipractice.dto.request.AiPracticeSessionCreateRequest;
import com.team.dating_backend.aipractice.dto.response.AiPracticeChatAcceptedResponse;
import com.team.dating_backend.aipractice.dto.response.AiPracticeChatPageResponse;
import com.team.dating_backend.aipractice.dto.response.AiPracticeSessionPageResponse;
import com.team.dating_backend.aipractice.dto.response.AiPracticeSessionResponse;
import com.team.dating_backend.aipractice.dto.response.AiPracticeUsageResponse;
import com.team.dating_backend.aipractice.service.AiPracticeChatService;
import com.team.dating_backend.aipractice.service.AiPracticeHistoryService;
import com.team.dating_backend.aipractice.service.AiPracticeSessionEndService;
import com.team.dating_backend.aipractice.service.AiPracticeSessionService;
import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ai-practice")
public class AiPracticeController {

    private final AiPracticeSessionService sessionService;
    private final AiPracticeSessionEndService sessionEndService;
    private final AiPracticeChatService chatService;
    private final AiPracticeHistoryService historyService;

    @PostMapping("/sessions")
    public ResponseEntity<SuccessResponse<AiPracticeSessionResponse>> startOrResume(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @Valid @RequestBody AiPracticeSessionCreateRequest request) {
        AiPracticeSessionResponse response = sessionService.startOrResume(
            principal.userId(), request);
        HttpStatus status = response.resumed() ? HttpStatus.OK : HttpStatus.CREATED;
        String message = response.resumed()
            ? "ai_practice_session_resumed"
            : "ai_practice_session_started";
        return ResponseEntity.status(status).body(SuccessResponse.of(message, response));
    }

    @GetMapping("/sessions")
    public SuccessResponse<AiPracticeSessionPageResponse> listSessions(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @RequestParam(required = false) @Min(1) Long cursor,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return SuccessResponse.of(
            "ai_practice_session_list_success",
            sessionService.listSessions(principal.userId(), cursor, size));
    }

    @GetMapping("/sessions/{sessionId}/chats")
    public SuccessResponse<AiPracticeChatPageResponse> getChats(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long sessionId,
        @RequestParam(required = false) @Min(1) Long cursor,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return SuccessResponse.of(
            "ai_practice_history_success",
            historyService.getHistory(principal.userId(), sessionId, cursor, size));
    }

    @PostMapping("/sessions/{sessionId}/chats")
    public ResponseEntity<SuccessResponse<AiPracticeChatAcceptedResponse>> sendMessage(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long sessionId,
        @Valid @RequestBody AiPracticeChatCreateRequest request) {
        AiPracticeChatAcceptedResponse response = chatService.sendMessage(
            principal.userId(), sessionId, request);
        return ResponseEntity.accepted()
            .body(SuccessResponse.of("ai_practice_chat_generation_accepted", response));
    }

    @PostMapping("/sessions/{sessionId}/chats/{chatId}/retry")
    public ResponseEntity<SuccessResponse<AiPracticeChatAcceptedResponse>> retry(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long sessionId,
        @PathVariable Long chatId) {
        AiPracticeChatAcceptedResponse response = chatService.retry(
            principal.userId(), sessionId, chatId);
        return ResponseEntity.accepted()
            .body(SuccessResponse.of("ai_practice_chat_retry_accepted", response));
    }

    @PostMapping("/sessions/{sessionId}/end")
    public SuccessResponse<AiPracticeSessionResponse> endSession(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable Long sessionId) {
        return SuccessResponse.of(
            "ai_practice_session_ended",
            sessionEndService.end(principal.userId(), sessionId));
    }

    @GetMapping("/usage/today")
    public SuccessResponse<AiPracticeUsageResponse> getTodayUsage(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal) {
        return SuccessResponse.of(
            "ai_practice_usage_success",
            chatService.getTodayUsage(principal.userId()));
    }
}
