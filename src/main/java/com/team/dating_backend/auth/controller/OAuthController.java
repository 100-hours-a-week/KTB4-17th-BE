package com.team.dating_backend.auth.controller;

import com.team.dating_backend.auth.dto.OAuthIdentity;
import com.team.dating_backend.auth.dto.SocialLoginResult;
import com.team.dating_backend.auth.enums.AuthProvider;
import com.team.dating_backend.auth.exception.OAuthInvalidRequestException;
import com.team.dating_backend.auth.service.OAuthProviderClient;
import com.team.dating_backend.auth.service.OAuthProviderClientRegistry;
import com.team.dating_backend.auth.service.OAuthStateService;
import com.team.dating_backend.auth.service.SocialLoginService;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class OAuthController {

    private final OAuthProviderClientRegistry oauthProviderClientRegistry;
    private final OAuthStateService oauthStateService;
    private final SocialLoginService socialLoginService;
    private final AuthLoginResponseFactory authLoginResponseFactory;

    @GetMapping("/{provider}")
    public ResponseEntity<Void> startLogin(
        @PathVariable("provider") String providerValue, HttpSession session) {
        AuthProvider provider = parseProvider(providerValue);
        authLoginResponseFactory.validateRedirectUris();
        OAuthProviderClient providerClient = oauthProviderClientRegistry.get(provider);
        String state = oauthStateService.createState(provider, session);

        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(providerClient.createAuthorizationUrl(state)))
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("Referrer-Policy", "no-referrer")
            .build();
    }

    @GetMapping("/{provider}/callback")
    public ResponseEntity<Void> handleCallback(
        @PathVariable("provider") String providerValue,
        @RequestParam(value = "code", required = false) String code,
        @RequestParam(value = "state", required = false) String state,
        HttpSession session) {
        AuthProvider provider = parseProvider(providerValue);
        validateCallbackParameters(code, state);
        authLoginResponseFactory.validateRedirectUris();
        OAuthProviderClient providerClient = oauthProviderClientRegistry.get(provider);
        oauthStateService.validateAndConsumeState(provider, state, session);

        OAuthIdentity identity = providerClient.requestIdentity(code);
        SocialLoginResult loginResult = socialLoginService.login(identity.provider(), identity.providerUserId());

        return authLoginResponseFactory.create(loginResult);
    }

    private AuthProvider parseProvider(String providerValue) {
        try {
            return AuthProvider.valueOf(providerValue.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new OAuthInvalidRequestException("Unsupported authentication provider");
        }
    }

    private void validateCallbackParameters(String code, String state) {
        if (!StringUtils.hasText(code) || !StringUtils.hasText(state)) {
            throw new OAuthInvalidRequestException("OAuth callback parameter is missing");
        }
    }
}
