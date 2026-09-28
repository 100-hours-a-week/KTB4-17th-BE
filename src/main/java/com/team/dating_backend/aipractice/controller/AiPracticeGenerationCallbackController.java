package com.team.dating_backend.aipractice.controller;

import com.team.dating_backend.aipractice.dto.request.AiPracticeGenerationCallbackRequest;
import com.team.dating_backend.aipractice.enums.AiPracticeErrorCode;
import com.team.dating_backend.aipractice.exception.AiPracticeBusinessException;
import com.team.dating_backend.aipractice.service.AiPracticeCallbackSecretVerifier;
import com.team.dating_backend.aipractice.service.AiPracticeGenerationCallbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/ai-practice")
public class AiPracticeGenerationCallbackController {

    private static final String CALLBACK_SECRET_HEADER = "X-AI-Callback-Secret";

    private final AiPracticeCallbackSecretVerifier secretVerifier;
    private final AiPracticeGenerationCallbackService callbackService;

    @PostMapping("/events")
    public ResponseEntity<Void> receiveGenerationEvent(
        @RequestHeader(
            name = CALLBACK_SECRET_HEADER,
            required = false
        ) String callbackSecret,
        @Valid @RequestBody AiPracticeGenerationCallbackRequest request) {
        if (!secretVerifier.matches(callbackSecret)) {
            throw new AiPracticeBusinessException(AiPracticeErrorCode.AI_CALLBACK_UNAUTHORIZED);
        }
        callbackService.receive(request);
        return ResponseEntity.noContent().build();
    }
}
