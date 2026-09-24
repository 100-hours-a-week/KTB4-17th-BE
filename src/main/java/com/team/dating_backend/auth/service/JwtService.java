package com.team.dating_backend.auth.service;

import com.team.dating_backend.auth.config.JwtProperties;
import com.team.dating_backend.auth.dto.PendingRegistrationTokenPayload;
import com.team.dating_backend.auth.enums.AuthProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import javax.crypto.SecretKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JwtService {

    private static final String PROVIDER_CLAIM = "provider";
    private static final String PROVIDER_USER_ID_CLAIM = "provider_user_id";
    private static final String PURPOSE_CLAIM = "purpose";
    private static final String PENDING_REGISTRATION_PURPOSE = "PENDING_REGISTRATION";
    private static final String SERVICE_AUTH_PURPOSE = "SERVICE_AUTH";

    private final JwtProperties jwtProperties;

    public String createPendingRegistrationToken(AuthProvider provider, String providerUserId) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(jwtProperties.getPendingExpirationMinutes(), ChronoUnit.MINUTES);

        return Jwts.builder()
            .claim(PROVIDER_CLAIM, provider.name())
            .claim(PROVIDER_USER_ID_CLAIM, providerUserId)
            .claim(PURPOSE_CLAIM, PENDING_REGISTRATION_PURPOSE)
            .issuedAt(Date.from(issuedAt))
            .expiration(Date.from(expiresAt))
            .signWith(signingKey())
            .compact();
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.getSecret()));
    }

    public PendingRegistrationTokenPayload parsePendingRegistrationToken(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtException("Pending registration token is missing");
        }

        Claims claims = Jwts.parser()
            .verifyWith(signingKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();

        String purpose = claims.get(PURPOSE_CLAIM, String.class);

        if (!PENDING_REGISTRATION_PURPOSE.equals(purpose)) {
            throw new JwtException("Invalid pending registration token purpose");
        }

        String providerValue = claims.get(PROVIDER_CLAIM, String.class);
        String providerUserId = claims.get(PROVIDER_USER_ID_CLAIM, String.class);

        if (providerValue == null
            || providerValue.isBlank()
            || providerUserId == null
            || providerUserId.isBlank()) {
            throw new JwtException("Required pending registration token claim is missing");
        }

        try {
            AuthProvider provider = AuthProvider.valueOf(providerValue);

            return new PendingRegistrationTokenPayload(provider, providerUserId);
        } catch (IllegalArgumentException exception) {
            throw new JwtException("Invalid authentication provider", exception);
        }
    }

    public String createServiceAuthToken(Long userId) {
        Instant issuedAt = Instant.now();

        Instant expiresAt = issuedAt.plus(jwtProperties.getServiceExpirationMinutes(), ChronoUnit.MINUTES);

        return Jwts.builder()
            .subject(userId.toString())
            .claim(PURPOSE_CLAIM, SERVICE_AUTH_PURPOSE)
            .issuedAt(Date.from(issuedAt))
            .expiration(Date.from(expiresAt))
            .signWith(signingKey())
            .compact();
    }

    public Long parseServiceAuthToken(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtException("Service auth token is missing");
        }

        Claims claims = Jwts.parser()
            .verifyWith(signingKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();

        String purpose = claims.get(PURPOSE_CLAIM, String.class);

        if (!SERVICE_AUTH_PURPOSE.equals(purpose)) {
            throw new JwtException("Invalid service auth token purpose");
        }

        String subject = claims.getSubject();

        if (subject == null || subject.isBlank()) {
            throw new JwtException("Service auth token subject is missing");
        }

        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException exception) {
            throw new JwtException("Invalid service auth token subject", exception);
        }
    }
}
