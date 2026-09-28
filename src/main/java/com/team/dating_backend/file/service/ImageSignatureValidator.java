package com.team.dating_backend.file.service;

import java.util.Arrays;
import org.springframework.stereotype.Component;

@Component
public class ImageSignatureValidator {

    public boolean isValid(String mimeType, byte[] signatureBytes) {
        if (mimeType == null || signatureBytes == null) {
            return false;
        }

        return switch (mimeType) {
            case "image/jpeg" -> startsWith(signatureBytes, 0xFF, 0xD8, 0xFF);
            case "image/png" -> startsWith(
                signatureBytes,
                0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "image/webp" -> signatureBytes.length >= 12
                && startsWith(signatureBytes, 0x52, 0x49, 0x46, 0x46)
                && signatureBytes[8] == 0x57
                && signatureBytes[9] == 0x45
                && signatureBytes[10] == 0x42
                && signatureBytes[11] == 0x50;
            default -> false;
        };
    }

    private boolean startsWith(byte[] value, int... expectedBytes) {
        if (value.length < expectedBytes.length) {
            return false;
        }

        byte[] expected = new byte[expectedBytes.length];
        for (int index = 0; index < expectedBytes.length; index++) {
            expected[index] = (byte) expectedBytes[index];
        }

        return Arrays.equals(
            Arrays.copyOf(value, expectedBytes.length),
            expected);
    }
}
