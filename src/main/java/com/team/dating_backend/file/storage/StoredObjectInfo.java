package com.team.dating_backend.file.storage;

import java.util.Arrays;

public record StoredObjectInfo(
    long fileSize,
    String contentType,
    String versionToken,
    byte[] signatureBytes) {

    public StoredObjectInfo {
        signatureBytes = signatureBytes == null ? null : Arrays.copyOf(signatureBytes, signatureBytes.length);
    }

    @Override
    public byte[] signatureBytes() {
        return signatureBytes == null ? null : Arrays.copyOf(signatureBytes, signatureBytes.length);
    }
}
