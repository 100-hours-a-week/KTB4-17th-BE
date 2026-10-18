package com.team.dating_backend.auth.service;

import com.team.dating_backend.auth.config.JwtProperties;
import com.team.dating_backend.auth.dto.IssuedAuthTokens;
import com.team.dating_backend.auth.dto.RefreshAuthTokenPayload;
import com.team.dating_backend.auth.entity.RefreshTokenSession;
import com.team.dating_backend.auth.enums.AuthErrorCode;
import com.team.dating_backend.auth.enums.RefreshTokenRevocationReason;
import com.team.dating_backend.auth.repository.RefreshTokenSessionRepository;
import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.security.UserAuthenticationService;
import io.jsonwebtoken.JwtException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthTokenService {

    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final RefreshTokenSessionRepository refreshTokenSessionRepository;
    private final UserAuthenticationService userAuthenticationService;

    @Transactional
    public IssuedAuthTokens issue(Long userId) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(
            jwtProperties.getRefreshExpirationDays(), ChronoUnit.DAYS);
        UUID sessionKey = UUID.randomUUID();
        String refreshToken = jwtService.createRefreshAuthToken(
            userId, sessionKey, expiresAt);

        refreshTokenSessionRepository.save(
            RefreshTokenSession.create(
                sessionKey,
                userId,
                hash(refreshToken),
                toLocalDateTime(issuedAt),
                toLocalDateTime(expiresAt)));

        return new IssuedAuthTokens(
            jwtService.createServiceAuthToken(userId), refreshToken, expiresAt);
    }

    @Transactional(noRollbackFor = JwtException.class)
    public IssuedAuthTokens rotate(String refreshToken) {
        RefreshAuthTokenPayload payload = jwtService.parseRefreshAuthToken(refreshToken);
        RefreshTokenSession session = refreshTokenSessionRepository
            .findBySessionKeyForUpdate(payload.sessionKey())
            .orElseThrow(() -> new JwtException(
                AuthErrorCode.REFRESH_TOKEN_SESSION_NOT_REGISTERED.name()));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        if (!session.isActiveAt(now) || !session.getUserId().equals(payload.userId())) {
            throw new JwtException(AuthErrorCode.REFRESH_TOKEN_SESSION_NOT_ACTIVE.name());
        }

        if (!session.matches(hash(refreshToken))) {
            session.revoke(RefreshTokenRevocationReason.REUSE_DETECTED, now);
            refreshTokenSessionRepository.saveAndFlush(session);
            throw new JwtException(AuthErrorCode.REFRESH_TOKEN_REUSE_DETECTED.name());
        }

        if (!userAuthenticationService.isAuthenticationAllowed(payload.userId())) {
            session.revoke(RefreshTokenRevocationReason.USER_NOT_ALLOWED, now);
            refreshTokenSessionRepository.saveAndFlush(session);
            throw new JwtException(AuthErrorCode.REFRESH_TOKEN_USER_NOT_ALLOWED.name());
        }

        Instant expiresAt = session.getExpiresAt().toInstant(ZoneOffset.UTC);
        String nextRefreshToken = jwtService.createRefreshAuthToken(
            payload.userId(), payload.sessionKey(), expiresAt);
        session.rotate(hash(nextRefreshToken), now);

        return new IssuedAuthTokens(
            jwtService.createServiceAuthToken(payload.userId()),
            nextRefreshToken,
            expiresAt);
    }

    @Transactional
    public void revokeCurrent(String refreshToken) {
        RefreshAuthTokenPayload payload;
        try {
            payload = jwtService.parseRefreshAuthToken(refreshToken);
        } catch (JwtException exception) {
            return;
        }

        refreshTokenSessionRepository.findBySessionKeyForUpdate(payload.sessionKey())
            .filter(session -> session.getUserId().equals(payload.userId()))
            .filter(session -> session.matches(hash(refreshToken)))
            .ifPresent(session -> session.revoke(
                RefreshTokenRevocationReason.LOGOUT,
                LocalDateTime.now(ZoneOffset.UTC)));
    }

    @Transactional
    public void revokeAllForUser(Long userId) {
        refreshTokenSessionRepository.revokeAllActiveByUserId(
            userId,
            RefreshTokenRevocationReason.USER_WITHDRAWAL,
            LocalDateTime.now(ZoneOffset.UTC));
    }

    @Transactional
    public int deleteExpiredSessions() {
        return refreshTokenSessionRepository.deleteExpired(LocalDateTime.now(ZoneOffset.UTC));
    }

    private byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                CommonErrorCode.INTERNAL_SERVER_ERROR.name(), exception);
        }
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
