package com.team.dating_backend.aipractice.dto.ai;

public final class AiPracticeAiPayloads {

    private AiPracticeAiPayloads() {}

    public record InitialGenerationRequest(
        Long practiceSessionId,
        Long chatId,
        Long userId,
        Long targetMemberId,
        int generationAttempt,
        String userMessage) {}

    public record ContinueGenerationRequest(
        Long practiceSessionId,
        Long chatId,
        int generationAttempt,
        String userMessage) {}

    public record GenerationAcceptedResponse(String aiSessionId) {}
}
