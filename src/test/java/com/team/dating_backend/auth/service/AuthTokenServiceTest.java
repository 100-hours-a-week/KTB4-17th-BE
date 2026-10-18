package com.team.dating_backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.auth.config.JwtProperties;
import com.team.dating_backend.auth.dto.IssuedAuthTokens;
import com.team.dating_backend.auth.dto.RefreshAuthTokenPayload;
import com.team.dating_backend.auth.entity.RefreshTokenSession;
import com.team.dating_backend.auth.enums.AuthErrorCode;
import com.team.dating_backend.auth.enums.RefreshTokenRevocationReason;
import com.team.dating_backend.auth.repository.RefreshTokenSessionRepository;
import com.team.dating_backend.security.UserAuthenticationService;
import io.jsonwebtoken.JwtException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuthTokenServiceTest {

    private static final Long USER_ID = 123L;
    private static final String TEST_SECRET = Base64.getEncoder()
        .encodeToString(
            "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));

    private JwtService jwtService;
    private RefreshTokenSessionRepository repository;
    private UserAuthenticationService userAuthenticationService;
    private AuthTokenService service;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(TEST_SECRET);
        properties.setPendingExpirationMinutes(10);
        properties.setServiceExpirationMinutes(15);
        properties.setRefreshExpirationDays(30);
        jwtService = new JwtService(properties);
        repository = mock(RefreshTokenSessionRepository.class);
        userAuthenticationService = mock(UserAuthenticationService.class);
        service = new AuthTokenService(
            jwtService, properties, repository, userAuthenticationService);
    }

    @Test
    void 로그인하면_토큰_원문_대신_해시를_가진_세션을_저장한다() throws Exception {
        IssuedAuthTokens tokens = service.issue(USER_ID);

        ArgumentCaptor<RefreshTokenSession> captor = ArgumentCaptor
            .forClass(RefreshTokenSession.class);
        verify(repository).save(captor.capture());
        RefreshTokenSession session = captor.getValue();
        RefreshAuthTokenPayload payload = jwtService.parseRefreshAuthToken(
            tokens.refreshToken());

        assertThat(session.getUserId()).isEqualTo(USER_ID);
        assertThat(session.getSessionKey()).isEqualTo(payload.sessionKey());
        assertThat(session.getCurrentTokenHash()).containsExactly(hash(tokens.refreshToken()));
        assertThat(session.getExpiresAt().toInstant(java.time.ZoneOffset.UTC))
            .isEqualTo(tokens.refreshExpiresAt());
    }

    @Test
    void Refresh_토큰을_갱신해도_최초_만료일은_연장되지_않고_토큰_정보만_변경된다() {
        IssuedAuthTokens initial = service.issue(USER_ID);
        RefreshTokenSession session = savedSession();
        RefreshAuthTokenPayload initialPayload = jwtService.parseRefreshAuthToken(
            initial.refreshToken());
        given(repository.findBySessionKeyForUpdate(initialPayload.sessionKey()))
            .willReturn(Optional.of(session));
        given(userAuthenticationService.isAuthenticationAllowed(USER_ID)).willReturn(true);

        IssuedAuthTokens rotated = service.rotate(initial.refreshToken());
        RefreshAuthTokenPayload rotatedPayload = jwtService.parseRefreshAuthToken(
            rotated.refreshToken());

        assertThat(rotated.refreshToken()).isNotEqualTo(initial.refreshToken());
        assertThat(rotatedPayload.sessionKey()).isEqualTo(initialPayload.sessionKey());
        assertThat(rotatedPayload.tokenId()).isNotEqualTo(initialPayload.tokenId());
        assertThat(rotatedPayload.expiresAt()).isEqualTo(initialPayload.expiresAt());
        assertThat(rotated.refreshExpiresAt()).isEqualTo(initial.refreshExpiresAt());
        assertThat(session.matches(hashUnchecked(rotated.refreshToken()))).isTrue();
        assertThat(session.matches(hashUnchecked(initial.refreshToken()))).isFalse();
    }

    @Test
    void 이미_교체된_Refresh_토큰이_재사용되면_해당_로그인_세션을_폐기한다() {
        IssuedAuthTokens initial = service.issue(USER_ID);
        RefreshTokenSession session = savedSession();
        RefreshAuthTokenPayload payload = jwtService.parseRefreshAuthToken(
            initial.refreshToken());
        given(repository.findBySessionKeyForUpdate(payload.sessionKey()))
            .willReturn(Optional.of(session));
        given(userAuthenticationService.isAuthenticationAllowed(USER_ID)).willReturn(true);
        IssuedAuthTokens rotated = service.rotate(initial.refreshToken());

        assertThatThrownBy(() -> service.rotate(initial.refreshToken()))
            .isInstanceOf(JwtException.class)
            .hasMessage(AuthErrorCode.REFRESH_TOKEN_REUSE_DETECTED.name());
        assertThat(session.getRevocationReason())
            .isEqualTo(RefreshTokenRevocationReason.REUSE_DETECTED);
        assertThatThrownBy(() -> service.rotate(rotated.refreshToken()))
            .isInstanceOf(JwtException.class)
            .hasMessage(AuthErrorCode.REFRESH_TOKEN_SESSION_NOT_ACTIVE.name());
        verify(repository).saveAndFlush(session);
    }

    @Test
    void 로그아웃은_현재_토큰과_일치하는_세션만_폐기한다() {
        IssuedAuthTokens tokens = service.issue(USER_ID);
        RefreshTokenSession session = savedSession();
        RefreshAuthTokenPayload payload = jwtService.parseRefreshAuthToken(tokens.refreshToken());
        given(repository.findBySessionKeyForUpdate(payload.sessionKey()))
            .willReturn(Optional.of(session));

        service.revokeCurrent(tokens.refreshToken());

        assertThat(session.getRevocationReason())
            .isEqualTo(RefreshTokenRevocationReason.LOGOUT);
    }

    private RefreshTokenSession savedSession() {
        ArgumentCaptor<RefreshTokenSession> captor = ArgumentCaptor
            .forClass(RefreshTokenSession.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private byte[] hash(String token) throws Exception {
        return MessageDigest.getInstance("SHA-256")
            .digest(token.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] hashUnchecked(String token) {
        try {
            return hash(token);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
