package com.team.dating_backend.persona.kakao.dto.response;

import java.util.List;

public record KakaoConversationInspectResponse(
    List<KakaoConversationParticipantResponse> participants,
    String selectedSpeakerName,
    boolean speakerSelectionRequired) {}
