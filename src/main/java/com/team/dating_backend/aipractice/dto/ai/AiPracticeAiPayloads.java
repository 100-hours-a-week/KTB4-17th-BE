package com.team.dating_backend.aipractice.dto.ai;

public final class AiPracticeAiPayloads {

    private AiPracticeAiPayloads() {}

    public record InitialGenerationRequest(
        String requestId,
        Long practiceSessionId,
        Long turnId,
        Long userId,
        Long targetMemberId,
        int attempt,
        String userMessage) {}

    public record ContinueGenerationRequest(
        String requestId,
        Long practiceSessionId,
        Long turnId,
        int attempt,
        String userMessage) {}

    public record GenerationAcceptedResponse(String aiSessionId) {}
}
