package com.team.dating_backend.auth.service;

import com.team.dating_backend.auth.enums.AuthProvider;
import com.team.dating_backend.auth.exception.OAuthInvalidRequestException;
import jakarta.servlet.http.HttpSession;
import java.util.Enumeration;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OAuthStateService {

    private static final long STATE_TTL_MILLIS = 5 * 60 * 1000L;
    private static final String STATE_KEY_PREFIX = "OAUTH_STATE_";

    public String createState(AuthProvider provider, HttpSession session) {
        String state = UUID.randomUUID().toString();

        synchronized (session) {
            removeExpiredStates(provider, session);
            session.setAttribute(stateKey(provider, state), System.currentTimeMillis());
        }

        return state;
    }

    public void validateAndConsumeState(
        AuthProvider provider, String receivedState, HttpSession session) {
        synchronized (session) {
            String stateKey = stateKey(provider, receivedState);
            Object savedCreatedAt = session.getAttribute(stateKey);
            session.removeAttribute(stateKey);

            if (!(savedCreatedAt instanceof Long createdAt)) {
                throw new OAuthInvalidRequestException("OAuth state is missing");
            }

            long elapsedTime = System.currentTimeMillis() - createdAt;
            boolean stateNotExpired = elapsedTime >= 0 && elapsedTime <= STATE_TTL_MILLIS;

            if (!stateNotExpired) {
                throw new OAuthInvalidRequestException("OAuth state is invalid");
            }
        }
    }

    private void removeExpiredStates(AuthProvider provider, HttpSession session) {
        String providerStatePrefix = providerStatePrefix(provider);
        Enumeration<String> attributeNames = session.getAttributeNames();
        long now = System.currentTimeMillis();

        while (attributeNames.hasMoreElements()) {
            String attributeName = attributeNames.nextElement();

            if (!attributeName.startsWith(providerStatePrefix)) {
                continue;
            }

            Object createdAtValue = session.getAttribute(attributeName);

            if (!(createdAtValue instanceof Long createdAt)
                || now - createdAt < 0
                || now - createdAt > STATE_TTL_MILLIS) {
                session.removeAttribute(attributeName);
            }
        }
    }

    private String stateKey(AuthProvider provider, String state) {
        return providerStatePrefix(provider) + state;
    }

    private String providerStatePrefix(AuthProvider provider) {
        return STATE_KEY_PREFIX + provider.name() + "_";
    }
}
