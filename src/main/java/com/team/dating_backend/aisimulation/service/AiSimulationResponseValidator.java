package com.team.dating_backend.aisimulation.service;

import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.Area;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.Dimension;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.MatchingReport;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.PersonaBrief;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationResponse;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.Turn;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class AiSimulationResponseValidator {

    private static final int EXPECTED_TURNS = 10;
    private static final int EXPECTED_MESSAGE_COUNT = EXPECTED_TURNS * 2;
    private static final Set<String> GRADES = Set.of("GOOD", "OK", "CAUTION");
    private static final Set<String> AREAS = Set.of(
        "intimacy",
        "communication",
        "conflict",
        "ideal",
        "orientation");
    private static final Set<String> FITS = Set.of(
        "similar",
        "both_high",
        "both_low",
        "judged");
    private static final Set<String> CONFIDENCES = Set.of("LOW", "MEDIUM", "HIGH");

    public void validate(SimulationResponse response, Long meUserId, Long partnerUserId) {
        if (response == null
            || isBlank(response.simulationId())
            || response.turns() == null
            || response.turns() != EXPECTED_TURNS
            || response.createdAt() == null) {
            throw invalid();
        }
        validateParticipant(response.me(), meUserId);
        validateParticipant(response.partner(), partnerUserId);
        validateTranscript(response.transcript());
        validateReport(response.report(), response);
    }

    private void validateParticipant(PersonaBrief participant, Long expectedUserId) {
        if (participant == null
            || isBlank(participant.personaId())
            || !expectedUserId.toString().equals(participant.userId())
            || isBlank(participant.nickname())
            || participant.version() == null
            || participant.version() < 1
            || !score(participant.accuracy())) {
            throw invalid();
        }
    }

    private void validateTranscript(List<Turn> transcript) {
        if (transcript == null || transcript.size() != EXPECTED_MESSAGE_COUNT) {
            throw invalid();
        }
        for (int index = 0; index < transcript.size(); index++) {
            Turn turn = transcript.get(index);
            String expectedSpeaker = index % 2 == 0 ? "a" : "b";
            if (turn == null
                || turn.index() == null
                || turn.index() != index
                || !expectedSpeaker.equals(turn.speaker())
                || isBlank(turn.text())
                || turn.text().length() > 1_000) {
                throw invalid();
            }
        }
    }

    private void validateReport(MatchingReport report, SimulationResponse response) {
        if (report == null
            || !response.simulationId().equals(report.simulationId())
            || !response.me().personaId().equals(report.personaAId())
            || !response.partner().personaId().equals(report.personaBId())
            || report.overall() == null
            || !score(report.overall().score())
            || !GRADES.contains(report.overall().grade())
            || isBlank(report.overall().gradeLabel())
            || isBlank(report.overall().headline())
            || isBlank(report.overall().summary())
            || report.generatedAt() == null
            || !Set.of("llm", "template").contains(report.narrativeSource())
            || report.dateSuggestion() == null
            || !strings(report.dateSuggestion().suggested())
            || !strings(report.dateSuggestion().avoid())
            || report.confidence() == null
            || !score(report.confidence().accuracy())
            || !strings(report.confidence().lowDimensions())
            || report.highlights() == null
            || !strings(report.strengths())
            || !strings(report.cautions())
            || !strings(report.risks())) {
            throw invalid();
        }
        validateAreas(report.areas());
        report.highlights().forEach(highlight -> {
            if (highlight == null
                || !Set.of("click", "friction").contains(highlight.kind())
                || highlight.turnIndex() == null
                || highlight.turnIndex() < 0
                || highlight.turnIndex() >= EXPECTED_MESSAGE_COUNT
                || isBlank(highlight.quote())
                || isBlank(highlight.why())) {
                throw invalid();
            }
        });
    }

    private void validateAreas(List<Area> areas) {
        if (areas == null || areas.size() != AREAS.size()) {
            throw invalid();
        }
        Set<String> found = new HashSet<>();
        for (Area area : areas) {
            if (area == null
                || !AREAS.contains(area.area())
                || !found.add(area.area())
                || isBlank(area.label())
                || area.score() != null && !score(area.score())
                || area.score() == null != (area.grade() == null)
                || area.grade() == null != (area.gradeLabel() == null)
                || area.grade() != null && !GRADES.contains(area.grade())
                || isBlank(area.comment())
                || area.dimensions() == null
                || area.dimensions().isEmpty()) {
                throw invalid();
            }
            for (Dimension dimension : area.dimensions()) {
                if (dimension == null
                    || isBlank(dimension.dimension())
                    || isBlank(dimension.label())
                    || !score(dimension.a())
                    || !score(dimension.b())
                    || !FITS.contains(dimension.fit())
                    || dimension.score() != null && !score(dimension.score())
                    || isBlank(dimension.why())
                    || !CONFIDENCES.contains(dimension.confidence())) {
                    throw invalid();
                }
            }
        }
    }

    private boolean score(Integer value) {
        return value != null && value >= 0 && value <= 100;
    }

    private boolean strings(List<String> values) {
        return values != null && values.stream().noneMatch(this::isBlank);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private AiSimulationBusinessException invalid() {
        return new AiSimulationBusinessException(
            AiSimulationErrorCode.AI_SERVER_RESPONSE_INVALID);
    }
}
