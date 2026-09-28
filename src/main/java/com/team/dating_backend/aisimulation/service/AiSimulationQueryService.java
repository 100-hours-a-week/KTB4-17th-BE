package com.team.dating_backend.aisimulation.service;

import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationResponse;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.Turn;
import com.team.dating_backend.aisimulation.dto.response.AiSimulationResponses;
import com.team.dating_backend.aisimulation.entity.AiSimulationMessage;
import com.team.dating_backend.aisimulation.entity.AiSimulationReport;
import com.team.dating_backend.aisimulation.entity.AiSimulationSession;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.enums.AiSimulationReportStatus;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import com.team.dating_backend.aisimulation.repository.AiSimulationMessageRepository;
import com.team.dating_backend.aisimulation.repository.AiSimulationReportRepository;
import com.team.dating_backend.aisimulation.repository.AiSimulationSessionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class AiSimulationQueryService {

    private static final int LIST_LIMIT = 20;

    private final AiSimulationSessionRepository sessionRepository;
    private final AiSimulationMessageRepository messageRepository;
    private final AiSimulationReportRepository reportRepository;
    private final AiSimulationResponseMapper responseMapper;
    private final AiSimulationResponseValidator validator;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<AiSimulationResponses.Summary> list(Long userId) {
        return sessionRepository.findByUserIdAndStatusOrderByIdDesc(
            userId,
            AiSimulationSessionStatus.COMPLETED,
            PageRequest.of(0, LIST_LIMIT)).stream()
            .map(session -> responseMapper.toSummary(
                session.getId(),
                session.getCreatedAt(),
                storedResponse(session, completedReport(session.getId()))))
            .toList();
    }

    @Transactional(readOnly = true)
    public AiSimulationResponses.Detail get(Long userId, Long simulationId) {
        AiSimulationSession session = ownedCompletedSession(userId, simulationId);
        AiSimulationReport report = completedReport(simulationId);
        SimulationResponse response = storedResponse(session, report);
        List<Turn> transcript = messageRepository.findBySessionIdOrderByIdAsc(simulationId)
            .stream()
            .map(this::storedTurn)
            .toList();
        if (transcript.size() != response.transcript().size()) {
            throw invalidStoredResult();
        }
        return responseMapper.toDetail(
            simulationId, session.getCreatedAt(), response, transcript);
    }

    @Transactional(readOnly = true)
    public AiSimulationResponses.MatchingReport getReport(
        Long userId,
        Long simulationId) {
        AiSimulationSession session = ownedCompletedSession(userId, simulationId);
        SimulationResponse response = storedResponse(session, completedReport(simulationId));
        return responseMapper.report(simulationId, response.report());
    }

    private AiSimulationSession ownedCompletedSession(Long userId, Long simulationId) {
        return sessionRepository.findByIdAndUserIdAndStatus(
            simulationId, userId, AiSimulationSessionStatus.COMPLETED)
            .orElseThrow(this::notFound);
    }

    private AiSimulationReport completedReport(Long simulationId) {
        return reportRepository.findBySessionIdAndDeletedAtIsNull(simulationId)
            .filter(report -> report.getStatus() == AiSimulationReportStatus.COMPLETED)
            .orElseThrow(this::notFound);
    }

    private SimulationResponse storedResponse(
        AiSimulationSession session,
        AiSimulationReport report) {
        try {
            SimulationResponse response = objectMapper.convertValue(
                report.getDetailResult(), SimulationResponse.class);
            validator.validate(
                response,
                session.getUserId(),
                Long.valueOf(response.partner().userId()));
            return response;
        } catch (AiSimulationBusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidStoredResult();
        }
    }

    private Turn storedTurn(AiSimulationMessage message) {
        try {
            return objectMapper.convertValue(message.getContent(), Turn.class);
        } catch (RuntimeException exception) {
            throw invalidStoredResult();
        }
    }

    private AiSimulationBusinessException notFound() {
        return new AiSimulationBusinessException(
            AiSimulationErrorCode.SIMULATION_NOT_FOUND);
    }

    private AiSimulationBusinessException invalidStoredResult() {
        return new AiSimulationBusinessException(
            AiSimulationErrorCode.AI_SERVER_RESPONSE_INVALID);
    }
}
