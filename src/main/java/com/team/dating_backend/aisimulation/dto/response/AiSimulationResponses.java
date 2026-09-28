package com.team.dating_backend.aisimulation.dto.response;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

public final class AiSimulationResponses {

    private AiSimulationResponses() {}

    public record Participant(
        String personaId,
        Long userId,
        String nickname,
        int version,
        String headline,
        int accuracy) {}

    public record Message(int index, String speaker, String text) {}

    public record Overall(
        int score,
        String grade,
        String gradeLabel,
        String headline,
        String summary) {}

    public record Dimension(
        String dimension,
        String label,
        int a,
        int b,
        String fit,
        Integer score,
        String why,
        String confidence) {}

    public record Area(
        String area,
        String label,
        Integer score,
        String grade,
        String gradeLabel,
        String comment,
        List<Dimension> dimensions) {}

    public record Highlight(
        String kind,
        int turnIndex,
        String quote,
        String why) {}

    public record DateSuggestion(
        List<String> suggested,
        List<String> avoid,
        String comment) {}

    public record Confidence(
        int accuracy,
        List<String> lowDimensions,
        String note) {}

    public record MatchingReport(
        Long simulationId,
        String personaAId,
        String personaBId,
        Overall overall,
        List<Area> areas,
        List<Highlight> highlights,
        List<String> strengths,
        List<String> cautions,
        List<String> risks,
        DateSuggestion dateSuggestion,
        Confidence confidence,
        String narrativeSource,
        OffsetDateTime generatedAt) {}

    public record Detail(
        Long simulationId,
        Participant me,
        Participant partner,
        int turns,
        List<Message> transcript,
        MatchingReport report,
        LocalDateTime createdAt) {}

    public record Summary(
        Long simulationId,
        Participant me,
        Participant partner,
        int turns,
        int overallScore,
        String grade,
        String gradeLabel,
        String headline,
        LocalDateTime createdAt) {}
}
