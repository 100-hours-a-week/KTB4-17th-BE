package com.team.dating_backend.persona.kakao.service;

import com.team.dating_backend.auth.config.JwtProperties;
import com.team.dating_backend.persona.kakao.config.KakaoConversationProperties;
import com.team.dating_backend.persona.kakao.dto.KakaoJobToken;
import com.team.dating_backend.persona.kakao.enums.KakaoConversationErrorCode;
import com.team.dating_backend.persona.kakao.exception.KakaoConversationBusinessException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/** Proves job ownership without storing a user/job mapping in the backend. */
@Component
public class KakaoJobTokenService {

    private static final String PURPOSE_CLAIM = "purpose";
    private static final String JOB_ID_CLAIM = "job_id";
    private static final String PURPOSE = "KAKAO_JOB_QUERY";
    private static final int MAX_TOKEN_LENGTH = 4096;

    private final SecretKey signingKey;
    private final KakaoConversationProperties properties;

    public KakaoJobTokenService(JwtProperties jwtProperties, KakaoConversationProperties properties) {
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.getSecret()));
        this.properties = properties;
    }

    public KakaoJobToken issue(Long userId, String jobId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plusSeconds(properties.getJobTokenTtlSeconds());
        String token = Jwts.builder()
            .subject(userId.toString())
            .claim(PURPOSE_CLAIM, PURPOSE)
            .claim(JOB_ID_CLAIM, jobId)
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
            .signWith(signingKey)
            .compact();
        return new KakaoJobToken(token, expiresAt.atOffset(ZoneOffset.UTC));
    }

    public void verify(Long userId, String jobId, String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw failure(KakaoConversationErrorCode.KAKAO_JOB_TOKEN_INVALID);
        }
        try {
            Claims claims = Jwts.parser().verifyWith(signingKey).build()
                .parseSignedClaims(token).getPayload();
            if (!PURPOSE.equals(claims.get(PURPOSE_CLAIM, String.class))
                || claims.getIssuedAt() == null || claims.getExpiration() == null
                || claims.getSubject() == null || claims.get(JOB_ID_CLAIM, String.class) == null) {
                throw failure(KakaoConversationErrorCode.KAKAO_JOB_TOKEN_INVALID);
            }
            if (!userId.toString().equals(claims.getSubject())
                || !jobId.equals(claims.get(JOB_ID_CLAIM, String.class))) {
                throw failure(KakaoConversationErrorCode.KAKAO_JOB_ACCESS_DENIED);
            }
        } catch (ExpiredJwtException exception) {
            throw failure(KakaoConversationErrorCode.KAKAO_JOB_TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException exception) {
            // Token parsing exceptions can contain the token/claims; never attach them.
            throw failure(KakaoConversationErrorCode.KAKAO_JOB_TOKEN_INVALID);
        }
    }

    private KakaoConversationBusinessException failure(KakaoConversationErrorCode code) {
        return new KakaoConversationBusinessException(code);
    }
}
