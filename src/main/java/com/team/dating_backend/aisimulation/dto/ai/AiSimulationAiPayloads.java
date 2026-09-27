package com.team.dating_backend.aisimulation.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;

public final class AiSimulationAiPayloads {

    private AiSimulationAiPayloads() {}

    public record SimulationRequest(
        @JsonProperty("me_user_id") String meUserId,
        @JsonProperty("partner_user_id") String partnerUserId,
        int turns) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PersonaBrief(
        @JsonProperty("persona_id") String personaId,
        @JsonProperty("user_id") String userId,
        String nickname,
        Integer version,
        String headline,
        Integer accuracy) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Turn(Integer index, String speaker, String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Overall(
        Integer score,
        String grade,
        @JsonProperty("grade_label") String gradeLabel,
        String headline,
        String summary) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Dimension(
        String dimension,
        String label,
        Integer a,
        Integer b,
        String fit,
        Integer score,
        String why,
        String confidence) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Area(
        String area,
        String label,
        Integer score,
        String grade,
        @JsonProperty("grade_label") String gradeLabel,
        String comment,
        List<Dimension> dimensions) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Highlight(
        String kind,
        @JsonProperty("turn_index") Integer turnIndex,
        String quote,
        String why) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DateSuggestion(
        List<String> suggested,
        List<String> avoid,
        String comment) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Confidence(
        Integer accuracy,
        @JsonProperty("low_dimensions") List<String> lowDimensions,
        String note) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MatchingReport(
        @JsonProperty("simulation_id") String simulationId,
        @JsonProperty("persona_a_id") String personaAId,
        @JsonProperty("persona_b_id") String personaBId,
        Overall overall,
        List<Area> areas,
        List<Highlight> highlights,
        List<String> strengths,
        List<String> cautions,
        List<String> risks,
        @JsonProperty("date_suggestion") DateSuggestion dateSuggestion,
        Confidence confidence,
        @JsonProperty("narrative_source") String narrativeSource,
        @JsonProperty("generated_at") OffsetDateTime generatedAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SimulationResponse(
        @JsonProperty("simulation_id") String simulationId,
        PersonaBrief me,
        PersonaBrief partner,
        Integer turns,
        List<Turn> transcript,
        MatchingReport report,
        @JsonProperty("created_at") OffsetDateTime createdAt) {}
}
