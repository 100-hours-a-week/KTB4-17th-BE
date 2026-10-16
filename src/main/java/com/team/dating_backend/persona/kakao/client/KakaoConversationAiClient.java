package com.team.dating_backend.persona.kakao.client;

import com.team.dating_backend.persona.kakao.dto.ai.KakaoAiPayloads.JobCreated;

public interface KakaoConversationAiClient {

    JobCreated submit(Long userId, String speakerName, String filename, byte[] bytes);
}
