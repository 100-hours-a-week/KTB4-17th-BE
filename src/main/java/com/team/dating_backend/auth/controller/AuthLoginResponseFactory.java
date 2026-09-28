package com.team.dating_backend.auth.controller;

import com.team.dating_backend.auth.config.AuthWebProperties;
import com.team.dating_backend.auth.dto.SocialLoginResult;
import com.team.dating_backend.auth.enums.LoginDestination;
import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.auth.service.OAuthLoginCodeService;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.util.Arrays;
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
    private final OAuthLoginCodeService oauthLoginCodeService;
    private final AuthCookieFactory authCookieFactory;
    private final AuthWebProperties authWebProperties;

    public ResponseEntity<Void> create(SocialLoginResult loginResult, HttpSession session) {
        if (loginResult instanceof SocialLoginResult.Authenticated authenticated) {
            String serviceToken = jwtService.createServiceAuthToken(authenticated.userId());
            String loginCode = oauthLoginCodeService.issue(serviceToken, session);

            return redirect(
                withLoginCode(destinationFor(authenticated.destination()), loginCode),
                authCookieFactory.deleteAccessToken(),
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

    public ResponseEntity<Void> redirectToLoginPage() {
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(loginDestination())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("Referrer-Policy", "no-referrer")
            .build();
    }

    public void validateRedirectUris() {
        loginDestination();
        destinationFor(LoginDestination.SERVICE);
        destinationFor(LoginDestination.REGISTRATION);
        destinationFor(LoginDestination.ONBOARDING);
    }

    private ResponseEntity<Void> redirect(URI destination, ResponseCookie... cookies) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.FOUND)
            .location(destination)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("Referrer-Policy", "no-referrer");

        Arrays.stream(cookies)
            .map(ResponseCookie::toString)
            .forEach(cookie -> response.header(HttpHeaders.SET_COOKIE, cookie));

        return response.build();
    }

    private URI withLoginCode(URI destination, String loginCode) {
        String destinationValue = destination.toASCIIString();
        int fragmentStart = destinationValue.indexOf('#');
        String beforeFragment = fragmentStart >= 0
            ? destinationValue.substring(0, fragmentStart)
            : destinationValue;
        String fragment = fragmentStart >= 0
            ? destinationValue.substring(fragmentStart + 1)
            : "";

        String separator = fragment.isEmpty()
            ? ""
            : fragment.contains("?")
                ? (fragment.endsWith("?") || fragment.endsWith("&") ? "" : "&")
                : "?";

        return URI.create(
            beforeFragment + "#" + fragment + separator + "auth_code=" + loginCode);
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

    private URI loginDestination() {
        String redirectUri = StringUtils.hasText(authWebProperties.getLoginRedirectUri())
            ? authWebProperties.getLoginRedirectUri()
            : authWebProperties.getServiceRedirectUri();

        if (!StringUtils.hasText(redirectUri)) {
            throw new IllegalStateException("Authentication login redirect URI is not configured");
        }

        URI redirectDestination = URI.create(redirectUri);

        if (!redirectDestination.isAbsolute()) {
            throw new IllegalStateException("Authentication login redirect URI must be absolute");
        }

        return redirectDestination;
    }
}
