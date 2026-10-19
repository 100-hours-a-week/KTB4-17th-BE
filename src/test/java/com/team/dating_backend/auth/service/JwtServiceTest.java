package com.team.dating_backend.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.team.dating_backend.auth.config.JwtProperties;
import com.team.dating_backend.auth.enums.AuthErrorCode;
import com.team.dating_backend.auth.enums.AuthProvider;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String TEST_SECRET = Base64.getEncoder()
        .encodeToString(
            "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecret(TEST_SECRET);
        jwtProperties.setPendingExpirationMinutes(10);
        jwtProperties.setServiceExpirationMinutes(15);
        jwtProperties.setRefreshExpirationDays(30);

        jwtService = new JwtService(jwtProperties);
    }

    @Test
    void 서비스_인증_토큰에서_userId를_추출한다() {
        // given
        String token = jwtService.createServiceAuthToken(123L);

        // when
        Long userId = jwtService.parseServiceAuthToken(token);

        // then
        assertEquals(123L, userId);
    }

    @Test
    void Pending_토큰은_서비스_인증_토큰으로_인정하지_않는다() {
        // given
        String pendingToken = jwtService.createPendingRegistrationToken(AuthProvider.KAKAO,
            "kakao-123", "Alice");

        // when & then
        assertThrows(JwtException.class, () -> jwtService.parseServiceAuthToken(pendingToken));
    }

    @Test
    void Pending_등록_토큰에서_카카오톡_닉네임을_추출한다() {
        String token = jwtService.createPendingRegistrationToken(
            AuthProvider.KAKAO, "kakao-123", "Alice");

        var payload = jwtService.parsePendingRegistrationToken(token);

        assertEquals(AuthProvider.KAKAO, payload.provider());
        assertEquals("kakao-123", payload.providerUserId());
        assertEquals("Alice", payload.kakaoTalkNickname());
    }

    @Test
    void 카카오톡_닉네임_claim이_없는_Pending_등록_토큰은_거절한다() {
        Instant now = Instant.now();
        String legacyToken = Jwts.builder()
            .claim("provider", AuthProvider.KAKAO.name())
            .claim("provider_user_id", "kakao-123")
            .claim("purpose", "PENDING_REGISTRATION")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(600)))
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(TEST_SECRET)))
            .compact();

        JwtException exception = assertThrows(
            JwtException.class,
            () -> jwtService.parsePendingRegistrationToken(legacyToken));

        assertEquals(
            AuthErrorCode.PENDING_REGISTRATION_NICKNAME_MISSING.name(),
            exception.getMessage());
    }

    @Test
    void 토큰이_없으면_서비스_인증에_실패한다() {
        // when & then
        assertThrows(JwtException.class, () -> jwtService.parseServiceAuthToken(null));
    }

    @Test
    void 만료된_서비스_토큰은_인증에_실패한다() {
        // given
        JwtProperties expiredProperties = new JwtProperties();
        expiredProperties.setSecret(TEST_SECRET);
        expiredProperties.setPendingExpirationMinutes(10);
        expiredProperties.setServiceExpirationMinutes(-1);
        expiredProperties.setRefreshExpirationDays(30);

        JwtService expiredJwtService = new JwtService(expiredProperties);
        String expiredToken = expiredJwtService.createServiceAuthToken(123L);

        // when & then
        assertThrows(
            JwtException.class, () -> expiredJwtService.parseServiceAuthToken(expiredToken));
    }

    @Test
    void 다른_서명키로_발급된_토큰은_인증에_실패한다() {
        // given
        String token = jwtService.createServiceAuthToken(123L);

        JwtProperties differentProperties = new JwtProperties();
        differentProperties.setSecret(
            Base64.getEncoder()
                .encodeToString(
                    "different-secret-0123456789012345"
                        .getBytes(StandardCharsets.UTF_8)));
        differentProperties.setServiceExpirationMinutes(60);
        differentProperties.setRefreshExpirationDays(30);

        JwtService differentJwtService = new JwtService(differentProperties);

        // when & then
        assertThrows(JwtException.class, () -> differentJwtService.parseServiceAuthToken(token));
    }

    @Test
    void Refresh_토큰에서_userId를_추출하고_Service_토큰과_구분한다() {
        UUID sessionKey = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(3600);
        String refreshToken = jwtService.createRefreshAuthToken(
            456L, sessionKey, expiresAt);

        var payload = jwtService.parseRefreshAuthToken(refreshToken);
        assertEquals(456L, payload.userId());
        assertEquals(sessionKey, payload.sessionKey());
        assertEquals(expiresAt.getEpochSecond(), payload.expiresAt().getEpochSecond());
        assertThrows(
            JwtException.class,
            () -> jwtService.parseRefreshAuthToken(jwtService.createServiceAuthToken(456L)));
    }

    @Test
    void 만료된_Refresh_토큰은_인증에_실패한다() {
        JwtProperties expiredProperties = new JwtProperties();
        expiredProperties.setSecret(TEST_SECRET);
        expiredProperties.setRefreshExpirationDays(-1);

        JwtService expiredJwtService = new JwtService(expiredProperties);
        String expiredToken = expiredJwtService.createRefreshAuthToken(
            456L, UUID.randomUUID(), Instant.now().minusSeconds(1));

        assertThrows(
            JwtException.class, () -> expiredJwtService.parseRefreshAuthToken(expiredToken));
    }
}
