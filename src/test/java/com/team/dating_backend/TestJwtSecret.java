package com.team.dating_backend;

import java.security.SecureRandom;
import java.util.Base64;

public final class TestJwtSecret {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TestJwtSecret() {}

    public static String generate() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
