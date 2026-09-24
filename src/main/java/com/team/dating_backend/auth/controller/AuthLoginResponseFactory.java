package com.team.dating_backend.auth.controller;

import com.team.dating_backend.auth.config.AuthWebProperties;
import com.team.dating_backend.auth.dto.SocialLoginResult;
import com.team.dating_backend.auth.enums.LoginDestination;
import com.team.dating_backend.auth.service.JwtService;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class AuthLoginResponseFactory {

    private final JwtService jwtService;
    private final AuthCookieFactory authCookieFactory;
    private final AuthWebProperties authWebProperties;

    public ResponseEntity<Void> create(SocialLoginResult loginResult) {
        if (loginResult instanceof SocialLoginResult.Authenticated authenticated) {
            String serviceToken = jwtService.createServiceAuthToken(authenticated.userId());

            return redirect(
                destinationFor(authenticated.destination()),
                authCookieFactory.accessToken(serviceToken),
                authCookieFactory.deletePendingRegistrationToken());
        }

        SocialLoginResult.PendingRegistration pendingRegistration = (SocialLoginResult.PendingRegistration) loginResult;
        String pendingToken = jwtService.createPendingRegistrationToken(
            pendingRegistration.provider(), pendingRegistration.providerUserId());

        return redirect(
            destinationFor(LoginDestination.REGISTRATION),
            authCookieFactory.pendingRegistrationToken(pendingToken),
            authCookieFactory.deleteAccessToken());
    }

    public void validateRedirectUris() {
        destinationFor(LoginDestination.SERVICE);
        destinationFor(LoginDestination.REGISTRATION);
        destinationFor(LoginDestination.ONBOARDING);
    }

    private ResponseEntity<Void> redirect(
        URI destination, ResponseCookie issuedCookie, ResponseCookie deletedCookie) {
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(destination)
            .header(HttpHeaders.SET_COOKIE, issuedCookie.toString())
            .header(HttpHeaders.SET_COOKIE, deletedCookie.toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("Referrer-Policy", "no-referrer")
            .build();
    }

    private URI destinationFor(LoginDestination destination) {
        String redirectUri = switch (destination) {
            case SERVICE -> authWebProperties.getServiceRedirectUri();
            case REGISTRATION -> authWebProperties.getRegistrationRedirectUri();
            case ONBOARDING -> authWebProperties.getOnboardingRedirectUri();
        };

        if (!StringUtils.hasText(redirectUri)) {
            throw new IllegalStateException("Authentication redirect URI is not configured");
        }

        URI redirectDestination = URI.create(redirectUri);

        if (!redirectDestination.isAbsolute()) {
            throw new IllegalStateException("Authentication redirect URI must be absolute");
        }

        return redirectDestination;
    }
}
