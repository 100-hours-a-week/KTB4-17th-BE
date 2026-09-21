package com.team.dating_backend.auth.service;

import com.team.dating_backend.auth.enums.AuthProvider;
import com.team.dating_backend.auth.exception.OAuthInvalidRequestException;
import jakarta.servlet.http.HttpSession;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OAuthStateService {

    private static final long STATE_TTL_MILLIS = 5 * 60 * 1000L;
    private static final String STATE_KEY_PREFIX = "OAUTH_STATE_";
    private static final String STATE_CREATED_AT_KEY_PREFIX = "OAUTH_STATE_CREATED_AT_";

    public String createState(AuthProvider provider, HttpSession session) {
        String state = UUID.randomUUID().toString();

        session.setAttribute(stateKey(provider), state);
        session.setAttribute(stateCreatedAtKey(provider), System.currentTimeMillis());

        return state;
    }

    public void validateAndConsumeState(
            AuthProvider provider, String receivedState, HttpSession session) {
        String stateKey = stateKey(provider);
        String createdAtKey = stateCreatedAtKey(provider);

        Object savedState = session.getAttribute(stateKey);
        Object savedCreatedAt = session.getAttribute(createdAtKey);

        session.removeAttribute(stateKey);
        session.removeAttribute(createdAtKey);

        if (!(savedState instanceof String state) || !(savedCreatedAt instanceof Long createdAt)) {
            throw new OAuthInvalidRequestException("OAuth state is missing");
        }

        long elapsedTime = System.currentTimeMillis() - createdAt;
        boolean stateNotExpired = elapsedTime >= 0 && elapsedTime <= STATE_TTL_MILLIS;

        if (!Objects.equals(state, receivedState) || !stateNotExpired) {
            throw new OAuthInvalidRequestException("OAuth state is invalid");
        }
    }

    private String stateKey(AuthProvider provider) {
        return STATE_KEY_PREFIX + provider.name();
    }

    private String stateCreatedAtKey(AuthProvider provider) {
        return STATE_CREATED_AT_KEY_PREFIX + provider.name();
    }
}
