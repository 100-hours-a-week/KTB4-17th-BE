package com.team.dating_backend.auth.service;

import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Enumeration;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class OAuthLoginCodeService {

    private static final long CODE_TTL_MILLIS = 2 * 60 * 1000L;
    private static final int CODE_BYTES = 32;
    private static final String SESSION_CODE_PREFIX = "OAUTH_LOGIN_CODE_";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public String issue(String accessToken, HttpSession session) {
        if (!StringUtils.hasText(accessToken)) {
            throw new IllegalArgumentException("Access token must not be blank");
        }
        if (session == null) {
            throw new IllegalArgumentException("OAuth login session is required");
        }

        synchronized (session) {
            removeExpiredCodes(session);

            String code;
            String attributeName;
            do {
                byte[] randomBytes = new byte[CODE_BYTES];
                SECURE_RANDOM.nextBytes(randomBytes);
                code = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
                attributeName = codeAttributeName(code);
            } while (session.getAttribute(attributeName) != null);

            session.setAttribute(
                attributeName,
                new LoginCodeGrant(accessToken, System.currentTimeMillis()));
            return code;
        }
    }

    public Optional<String> consume(String code, HttpSession session) {
        if (session == null || !isValidCode(code)) {
            return Optional.empty();
        }

        synchronized (session) {
            String attributeName = codeAttributeName(code);
            Object savedGrant = session.getAttribute(attributeName);
            session.removeAttribute(attributeName);

            if (!(savedGrant instanceof LoginCodeGrant grant)) {
                return Optional.empty();
            }

            long elapsedMillis = System.currentTimeMillis() - grant.createdAtMillis();
            if (elapsedMillis < 0 || elapsedMillis > CODE_TTL_MILLIS) {
                return Optional.empty();
            }

            return Optional.of(grant.accessToken());
        }
    }

    private void removeExpiredCodes(HttpSession session) {
        Enumeration<String> attributeNames = session.getAttributeNames();
        long now = System.currentTimeMillis();

        while (attributeNames.hasMoreElements()) {
            String attributeName = attributeNames.nextElement();
            if (!attributeName.startsWith(SESSION_CODE_PREFIX)) {
                continue;
            }

            Object savedGrant = session.getAttribute(attributeName);
            if (!(savedGrant instanceof LoginCodeGrant grant)) {
                session.removeAttribute(attributeName);
                continue;
            }

            long elapsedMillis = now - grant.createdAtMillis();
            if (elapsedMillis < 0 || elapsedMillis > CODE_TTL_MILLIS) {
                session.removeAttribute(attributeName);
            }
        }
    }

    private boolean isValidCode(String code) {
        return code != null && code.matches("[A-Za-z0-9_-]{43}");
    }

    private String codeAttributeName(String code) {
        return SESSION_CODE_PREFIX + code;
    }

    private record LoginCodeGrant(String accessToken, long createdAtMillis)
        implements
            Serializable {}
}
