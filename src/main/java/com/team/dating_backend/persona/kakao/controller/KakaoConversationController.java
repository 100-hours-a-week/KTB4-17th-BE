package com.team.dating_backend.persona.kakao.controller;

import com.team.dating_backend.common.dto.response.SuccessResponse;
import com.team.dating_backend.persona.kakao.dto.response.KakaoConversationInspectResponse;
import com.team.dating_backend.persona.kakao.dto.response.KakaoExtractionResponse;
import com.team.dating_backend.persona.kakao.service.KakaoConversationService;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
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

    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SuccessResponse<KakaoExtractionResponse>> extract(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal,
        @RequestPart MultipartFile file,
        @RequestParam(required = false) String speakerName) {
        KakaoExtractionResponse response = service.extract(principal.userId(), file, speakerName);
        return ResponseEntity.accepted()
            .cacheControl(CacheControl.noStore())
            .body(SuccessResponse.of("kakao_extraction_requested", response));
    }
}
