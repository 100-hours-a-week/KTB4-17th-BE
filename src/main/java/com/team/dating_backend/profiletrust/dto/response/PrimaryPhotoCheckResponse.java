package com.team.dating_backend.profiletrust.dto.response;

import com.team.dating_backend.profiletrust.dto.PrimaryPhotoCheckResult;
import java.util.List;

public record PrimaryPhotoCheckResponse(boolean passed, List<String> reasonCodes) {

    public static PrimaryPhotoCheckResponse from(PrimaryPhotoCheckResult result) {
        return new PrimaryPhotoCheckResponse(result.passed(), result.reasonCodes());
    }
}
