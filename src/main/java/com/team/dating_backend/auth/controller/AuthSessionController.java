package com.team.dating_backend.auth.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthSessionController {

    private final AuthCookieFactory authCookieFactory;

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, authCookieFactory.deleteAccessToken().toString())
            .header(
                HttpHeaders.SET_COOKIE,
                authCookieFactory.deletePendingRegistrationToken().toString())
            .build();
    }
}
