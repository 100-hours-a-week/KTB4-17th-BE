package com.team.dating_backend.auth.controller;

import com.team.dating_backend.auth.dto.AuthErrorResponse;
import com.team.dating_backend.auth.dto.IssuedAuthTokens;
import com.team.dating_backend.auth.dto.request.AuthTokenExchangeRequest;
import com.team.dating_backend.auth.dto.response.AuthTokenResponse;
import com.team.dating_backend.auth.service.AuthTokenService;
import com.team.dating_backend.auth.service.OAuthLoginCodeService;
import com.team.dating_backend.common.enums.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthTokenController {

    private final OAuthLoginCodeService oauthLoginCodeService;
    private final AuthTokenService authTokenService;
    private final AuthCookieFactory authCookieFactory;

    @PostMapping("/token/exchange")
    public ResponseEntity<?> exchange(
        @Valid @RequestBody AuthTokenExchangeRequest request,
        HttpServletRequest servletRequest) {
        HttpSession session = servletRequest.getSession(false);
        Optional<Long> userId = oauthLoginCodeService.consume(
            request.code(), session);
        HttpHeaders headers = noStoreHeaders();

        if (userId.isEmpty()) {
            return new ResponseEntity<>(
                new AuthErrorResponse(CommonErrorCode.AUTH_REQUIRED.name()),
                headers,
                CommonErrorCode.AUTH_REQUIRED.status());
        }

        IssuedAuthTokens tokens = authTokenService.issue(userId.get());
        headers.add(
            HttpHeaders.SET_COOKIE,
            authCookieFactory.refreshToken(
                tokens.refreshToken(), tokens.refreshExpiresAt()).toString());

        return new ResponseEntity<>(
            AuthTokenResponse.bearer(tokens.accessToken()), headers, HttpStatus.OK);
    }

    private HttpHeaders noStoreHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore());
        headers.setPragma("no-cache");
        return headers;
    }
}
