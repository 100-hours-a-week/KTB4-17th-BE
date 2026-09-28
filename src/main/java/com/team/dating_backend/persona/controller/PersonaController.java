package com.team.dating_backend.persona.controller;

import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.persona.dto.request.PersonaAnswerRequest;
import com.team.dating_backend.persona.dto.response.PersonaConfirmationResponse;
import com.team.dating_backend.persona.dto.response.PersonaConversationResponse;
import com.team.dating_backend.persona.dto.response.PersonaDraftResponse;
import com.team.dating_backend.persona.service.PersonaOnboardingService;
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
@RequestMapping("/api/v1/persona")
public class PersonaController {

    private final PersonaOnboardingService service;

    @PostMapping("/onboarding/start")
    public ResponseEntity<SuccessResponse<PersonaConversationResponse>> start(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal) {
        PersonaConversationResponse response = service.start(principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(SuccessResponse.of("persona_onboarding_started", response));
    }

    @PostMapping("/onboarding/{sessionId}/answer")
    public SuccessResponse<PersonaConversationResponse> answer(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable String sessionId,
        @Valid @RequestBody PersonaAnswerRequest request) {
        return SuccessResponse.of(
            "persona_onboarding_answered",
            service.answer(principal.userId(), sessionId, request));
    }

    @PostMapping("/onboarding/{sessionId}/skip")
    public SuccessResponse<PersonaConversationResponse> skip(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable String sessionId) {
        return SuccessResponse.of(
            "persona_onboarding_skipped",
            service.skip(principal.userId(), sessionId));
    }

    @PostMapping("/onboarding/{sessionId}/finish")
    public SuccessResponse<PersonaConversationResponse> finish(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable String sessionId) {
        return SuccessResponse.of(
            "persona_onboarding_finished",
            service.finish(principal.userId(), sessionId));
    }

    @PostMapping("/{sessionId}/build")
    public SuccessResponse<PersonaDraftResponse> build(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable String sessionId) {
        return SuccessResponse.of(
            "persona_draft_built",
            service.build(principal.userId(), sessionId));
    }

    @PostMapping("/{personaId}/confirm")
    public SuccessResponse<PersonaConfirmationResponse> confirm(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable String personaId) {
        return SuccessResponse.of(
            "persona_confirmed",
            service.confirm(principal.userId(), personaId));
    }
}
