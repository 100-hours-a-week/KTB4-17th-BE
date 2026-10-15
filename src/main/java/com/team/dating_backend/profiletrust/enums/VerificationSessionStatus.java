package com.team.dating_backend.profiletrust.enums;

public enum VerificationSessionStatus {
    READY, PROCESSING, VERIFIED, NOT_VERIFIED, EXHAUSTED, EXPIRED;

    public boolean isOpen() {
        return this == READY || this == PROCESSING;
    }
}
