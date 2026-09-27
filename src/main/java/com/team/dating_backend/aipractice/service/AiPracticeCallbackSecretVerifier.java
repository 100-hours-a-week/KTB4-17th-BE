package com.team.dating_backend.aipractice.service;

import com.team.dating_backend.aipractice.config.AiPracticeProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiPracticeCallbackSecretVerifier {

    private final AiPracticeProperties properties;

    public boolean matches(String candidate) {
        String expected = properties.getCallbackSecret();
        if (expected == null || expected.isBlank() || candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            candidate.getBytes(StandardCharsets.UTF_8));
    }
}
