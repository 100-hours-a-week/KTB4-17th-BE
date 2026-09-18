package com.team.dating_backend.auth.service;

import com.team.dating_backend.auth.config.JwtProperties;
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

  private final JwtProperties jwtProperties;

  public String createPendingToken(String provider, String providerUserId) {
    Instant issuedAt = Instant.now();
    Instant expiresAt =
        issuedAt.plus(jwtProperties.getPendingExpirationMinutes(), ChronoUnit.MINUTES);

    SecretKey signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.getSecret()));

    return Jwts.builder()
        .claim("provider", provider)
        .claim("provider_user_id", providerUserId)
        .claim("purpose", "PENDING_ONBOARDING")
        .issuedAt(Date.from(issuedAt))
        .expiration(Date.from(expiresAt))
        .signWith(signingKey)
        .compact();
  }
}
