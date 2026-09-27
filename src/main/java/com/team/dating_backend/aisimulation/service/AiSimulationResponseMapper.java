package com.team.dating_backend.aisimulation.service;

import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads;
import com.team.dating_backend.aisimulation.dto.response.AiSimulationResponses;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AiSimulationResponseMapper {

    public AiSimulationResponses.Detail toDetail(
        Long simulationId,
        LocalDateTime createdAt,
        AiSimulationAiPayloads.SimulationResponse source,
        List<AiSimulationAiPayloads.Turn> transcript) {
        return new AiSimulationResponses.Detail(
            simulationId,
            participant(source.me()),
            participant(source.partner()),
            source.turns(),
            transcript.stream().map(this::message).toList(),
            report(simulationId, source.report()),
            createdAt);
    }

    public AiSimulationResponses.Summary toSummary(
        Long simulationId,
        LocalDateTime createdAt,
        AiSimulationAiPayloads.SimulationResponse source) {
        AiSimulationAiPayloads.Overall overall = source.report().overall();
        return new AiSimulationResponses.Summary(
            simulationId,
            participant(source.me()),
            participant(source.partner()),
            source.turns(),
            overall.score(),
            overall.grade(),
            overall.gradeLabel(),
            overall.headline(),
            createdAt);
    }

    public AiSimulationResponses.MatchingReport report(
        Long simulationId,
        AiSimulationAiPayloads.MatchingReport source) {
        return new AiSimulationResponses.MatchingReport(
            simulationId,
            source.personaAId(),
            source.personaBId(),
            overall(source.overall()),
            source.areas().stream().map(this::area).toList(),
            source.highlights().stream().map(this::highlight).toList(),
            List.copyOf(source.strengths()),
            List.copyOf(source.cautions()),
            List.copyOf(source.risks()),
            dateSuggestion(source.dateSuggestion()),
            confidence(source.confidence()),
            source.narrativeSource(),
            source.generatedAt());
    }

    private AiSimulationResponses.Participant participant(
        AiSimulationAiPayloads.PersonaBrief source) {
        return new AiSimulationResponses.Participant(
            source.personaId(),
            Long.valueOf(source.userId()),
            source.nickname(),
            source.version(),
            source.headline(),
            source.accuracy());
    }

    private AiSimulationResponses.Message message(AiSimulationAiPayloads.Turn source) {
        return new AiSimulationResponses.Message(
            source.index(), source.speaker(), source.text());
    }

    private AiSimulationResponses.Overall overall(AiSimulationAiPayloads.Overall source) {
        return new AiSimulationResponses.Overall(
            source.score(),
            source.grade(),
            source.gradeLabel(),
            source.headline(),
            source.summary());
    }

    private AiSimulationResponses.Area area(AiSimulationAiPayloads.Area source) {
        return new AiSimulationResponses.Area(
            source.area(),
            source.label(),
            source.score(),
            source.grade(),
            source.gradeLabel(),
            source.comment(),
            source.dimensions().stream().map(this::dimension).toList());
    }

    private AiSimulationResponses.Dimension dimension(
        AiSimulationAiPayloads.Dimension source) {
        return new AiSimulationResponses.Dimension(
            source.dimension(),
            source.label(),
            source.a(),
            source.b(),
            source.fit(),
            source.score(),
            source.why(),
            source.confidence());
    }

    private AiSimulationResponses.Highlight highlight(
        AiSimulationAiPayloads.Highlight source) {
        return new AiSimulationResponses.Highlight(
            source.kind(), source.turnIndex(), source.quote(), source.why());
    }

    private AiSimulationResponses.DateSuggestion dateSuggestion(
        AiSimulationAiPayloads.DateSuggestion source) {
        return new AiSimulationResponses.DateSuggestion(
            List.copyOf(source.suggested()),
            List.copyOf(source.avoid()),
            source.comment());
    }

    private AiSimulationResponses.Confidence confidence(
        AiSimulationAiPayloads.Confidence source) {
        return new AiSimulationResponses.Confidence(
            source.accuracy(),
            List.copyOf(source.lowDimensions()),
            source.note());
    }
}
