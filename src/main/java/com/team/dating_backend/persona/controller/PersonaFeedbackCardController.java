package com.team.dating_backend.persona.controller;

import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.common.exception.RequestValidationException;
import com.team.dating_backend.persona.dto.request.FeedbackCardAnswerRequest;
import com.team.dating_backend.persona.dto.response.FeedbackCardAnswerResponse;
import com.team.dating_backend.persona.dto.response.FeedbackCardResponse;
import com.team.dating_backend.persona.service.FeedbackCardService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/persona/feedback-cards")
public class PersonaFeedbackCardController {

    private final FeedbackCardService service;

    @PostMapping("/next")
    public ResponseEntity<SuccessResponse<FeedbackCardResponse>> next(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal) {
        FeedbackCardResponse card = service.next(principal.userId());
        if (card == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(SuccessResponse.of("persona_feedback_card_next_success", card));
    }

    @PostMapping("/{cardId}/answer")
    public SuccessResponse<FeedbackCardAnswerResponse> answer(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable String cardId,
        @Valid @RequestBody FeedbackCardAnswerRequest request) {
        if (cardId.isBlank() || cardId.length() > 64) {
            throw new RequestValidationException();
        }
        return SuccessResponse.of(
            "persona_feedback_card_answered",
            service.answer(principal.userId(), cardId, request));
    }
}
