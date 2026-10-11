package com.team.dating_backend.persona.kakao.dto;

import java.time.OffsetDateTime;

public record KakaoJobToken(String value, OffsetDateTime expiresAt) {}
