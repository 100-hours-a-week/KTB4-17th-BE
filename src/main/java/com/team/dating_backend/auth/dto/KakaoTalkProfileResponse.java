package com.team.dating_backend.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KakaoTalkProfileResponse(
    Long id,
    @JsonProperty("nickName") String nickname) {}
