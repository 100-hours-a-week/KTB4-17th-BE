package com.team.dating_backend.auth.controller;

import com.team.dating_backend.auth.service.KakaoOAuthService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class KakaoAuthController {

    private final KakaoOAuthService kakaoOAuthService;

    @GetMapping("/kakao")
    public ResponseEntity<Void> startKakaoLogin(HttpSession session) {
        String authorizationUrl = kakaoOAuthService.createAuthorizationUrl(session);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(authorizationUrl))
                .build();
    }
}
