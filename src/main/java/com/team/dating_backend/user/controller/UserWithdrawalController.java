package com.team.dating_backend.user.controller;

import com.team.dating_backend.auth.controller.AuthCookieFactory;
import com.team.dating_backend.security.ServiceAuthenticationPrincipal;
import com.team.dating_backend.user.service.UserWithdrawalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/me")
public class UserWithdrawalController {

    private final UserWithdrawalService userWithdrawalService;
    private final AuthCookieFactory authCookieFactory;

    @DeleteMapping
    public ResponseEntity<Void> withdraw(
        @AuthenticationPrincipal ServiceAuthenticationPrincipal principal) {
        userWithdrawalService.withdraw(principal.userId());

        return ResponseEntity.noContent()
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.PRAGMA, "no-cache")
            .header(
                HttpHeaders.SET_COOKIE,
                authCookieFactory.deleteAccessToken().toString(),
                authCookieFactory.deleteRefreshToken().toString(),
                authCookieFactory.deletePendingRegistrationToken().toString())
            .build();
    }
}
