package com.team.dating_backend.profiletrust.client;

import com.team.dating_backend.profiletrust.dto.ai.PrimaryPhotoAiPayloads.FrontalCheckResponse;
import com.team.dating_backend.profiletrust.dto.ai.PrimaryPhotoAiPayloads.SyntheticCheckResponse;

public interface PrimaryPhotoAiClient {

    FrontalCheckResponse checkFrontal(byte[] image, String contentType);

    SyntheticCheckResponse checkSynthetic(byte[] image, String contentType);
}
