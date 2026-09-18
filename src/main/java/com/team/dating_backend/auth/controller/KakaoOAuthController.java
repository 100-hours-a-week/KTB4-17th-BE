package com.team.dating_backend.auth.controller;

import com.team.dating_backend.auth.dto.KakaoTokenResponse;
import com.team.dating_backend.auth.dto.KakaoUserInfoResponse;
import com.team.dating_backend.auth.enums.AuthProvider;
import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.auth.service.KakaoOAuthService;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class KakaoOAuthController {

  private final KakaoOAuthService kakaoOAuthService;
  private final JwtService jwtService;

  @GetMapping("/kakao")
  public ResponseEntity<Void> startKakaoLogin(HttpSession session) {
    String authorizationUrl = kakaoOAuthService.createAuthorizationUrl(session);
    return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(authorizationUrl)).build();
  }

  @GetMapping("/kakao/callback")
  public ResponseEntity<Void> handleCallback(
      @RequestParam("code") String code, @RequestParam("state") String state, HttpSession session) {

    boolean validState = kakaoOAuthService.validateState(state, session);

    if (!validState) {
      return ResponseEntity.badRequest().build();
    }

    KakaoTokenResponse tokenResponse = kakaoOAuthService.requestAccessToken(code);

    String accessToken = tokenResponse.accessToken();

    KakaoUserInfoResponse userInfo = kakaoOAuthService.requestUserInfo(accessToken);

    Long kakaoUserId = userInfo.id();

    String providerUserId = String.valueOf(kakaoUserId);
    String pendingToken = jwtService.createPendingToken("KAKAO", providerUserId);

    ResponseCookie pendingCookie =
        ResponseCookie.from("PENDING_ONBOARDING_TOKEN", pendingToken)
            .httpOnly(true)
            .secure(false)
            .sameSite("Lax")
            .path("/")
            .build();
    return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, pendingCookie.toString()).build();
  }
}
