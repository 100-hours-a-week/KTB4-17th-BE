package com.team.dating_backend.persona.kakao.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class KakaoAiPayloads {

    private KakaoAiPayloads() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JobCreated(@JsonProperty("job_id") String jobId, String status) {}

}
