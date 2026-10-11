package com.team.dating_backend.persona.kakao.controller;

import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.persona.kakao.dto.response.KakaoConversationInspectResponse;
import com.team.dating_backend.persona.kakao.dto.response.KakaoConversationSubmissionResponse;
import com.team.dating_backend.persona.kakao.service.KakaoConversationService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/persona/kakao")
public class KakaoConversationController {

    private final KakaoConversationService service;

    @PostMapping(value = "/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SuccessResponse<KakaoConversationInspectResponse> inspect(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @RequestPart MultipartFile file) {
        return SuccessResponse.of("kakao_conversation_inspected", service.inspect(principal.userId(), file));
    }

    @PostMapping(value = "/submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SuccessResponse<KakaoConversationSubmissionResponse>> submit(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @RequestHeader(value = "Idempotency-Key", required = false) String requestKey,
        @RequestPart MultipartFile file,
        @RequestParam(required = false) String speakerName) {
        KakaoConversationSubmissionResponse response = service.submit(principal.userId(), requestKey, file,
            speakerName);
        return ResponseEntity.accepted()
            .location(URI.create("/api/v1/persona/kakao/submissions/" + response.submissionId()))
            .body(SuccessResponse.of("kakao_conversation_submitted", response));
    }

    @GetMapping("/submissions/{submissionId}")
    public SuccessResponse<KakaoConversationSubmissionResponse> getSubmission(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @PathVariable String submissionId) {
        return SuccessResponse.of("kakao_conversation_submission_fetched",
            service.getSubmission(principal.userId(), submissionId));
    }
}
