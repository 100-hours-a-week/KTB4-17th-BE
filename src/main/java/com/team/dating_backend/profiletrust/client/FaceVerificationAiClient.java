package com.team.dating_backend.profiletrust.client;

import com.team.dating_backend.profiletrust.dto.ai.FaceVerificationAiPayloads.ChallengeResponse;
import com.team.dating_backend.profiletrust.dto.ai.FaceVerificationAiPayloads.VerificationResponse;

public interface FaceVerificationAiClient {

    ChallengeResponse createChallenge();

    VerificationResponse verify(
        String challengeToken, byte[] primaryPhoto, String photoContentType,
        byte[] liveVideo, String videoContentType);

    default VerificationResponse verify(
        String challengeToken, byte[] primaryPhoto, String photoContentType,
        byte[] liveVideo, String videoContentType, int attemptNo) {
        return verify(challengeToken, primaryPhoto, photoContentType, liveVideo, videoContentType);
    }
}
