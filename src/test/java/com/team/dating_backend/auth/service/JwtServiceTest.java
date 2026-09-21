package com.team.dating_backend.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.team.dating_backend.auth.config.JwtProperties;
import com.team.dating_backend.auth.enums.AuthProvider;
import io.jsonwebtoken.JwtException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String TEST_SECRET =
            Base64.getEncoder()
                    .encodeToString(
                            "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecret(TEST_SECRET);
        jwtProperties.setPendingExpirationMinutes(10);
        jwtProperties.setServiceExpirationMinutes(60);

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
        String pendingToken =
                jwtService.createPendingRegistrationToken(AuthProvider.KAKAO, "kakao-123");

        // when & then
        assertThrows(JwtException.class, () -> jwtService.parseServiceAuthToken(pendingToken));
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

        JwtService differentJwtService = new JwtService(differentProperties);

        // when & then
        assertThrows(JwtException.class, () -> differentJwtService.parseServiceAuthToken(token));
    }
}
