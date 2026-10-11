package com.team.dating_backend.persona.kakao.client;

import com.team.dating_backend.persona.kakao.dto.ai.KakaoAiPayloads.JobCreated;
import com.team.dating_backend.persona.kakao.dto.ai.KakaoAiPayloads.JobResponse;

public interface KakaoConversationAiClient {

    void requireConfigured();

    JobCreated submit(Long userId, String speakerName, String filename, byte[] bytes);

    JobResponse getJob(String jobId);
}
