package com.team.dating_backend.aisimulation.service;

import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationResponse;
import com.team.dating_backend.aisimulation.entity.AiSimulationMessage;
import com.team.dating_backend.aisimulation.entity.AiSimulationReport;
import com.team.dating_backend.aisimulation.entity.AiSimulationSession;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import com.team.dating_backend.aisimulation.repository.AiSimulationMessageRepository;
import com.team.dating_backend.aisimulation.repository.AiSimulationReportRepository;
import com.team.dating_backend.aisimulation.repository.AiSimulationSessionRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class AiSimulationPersistenceService {

    private final AiSimulationSessionRepository sessionRepository;
    private final AiSimulationMessageRepository messageRepository;
    private final AiSimulationReportRepository reportRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public AiSimulationSession begin(Long userId) {
        LocalDateTime now = now();
        AiSimulationSession session = sessionRepository.save(
            new AiSimulationSession(userId, now));
        reportRepository.save(new AiSimulationReport(session, now));
        return session;
    }

    @Transactional
    public void complete(Long sessionId, SimulationResponse response) {
        AiSimulationSession session = requireInProgressSession(sessionId);
        AiSimulationReport report = reportRepository.findBySessionId(sessionId)
            .orElseThrow(this::notFound);
        LocalDateTime completedAt = now();

        List<AiSimulationMessage> messages = response.transcript().stream()
            .map(turn -> new AiSimulationMessage(
                session,
                toMap(turn),
                completedAt))
            .toList();
        messageRepository.saveAll(messages);
        report.complete(
            response.report().overall().score(),
            response.report().overall().summary(),
            toMap(response),
            completedAt);
        session.complete(completedAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long sessionId) {
        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.fail(now());
            reportRepository.findBySessionId(sessionId).ifPresent(AiSimulationReport::fail);
        });
    }

    private AiSimulationSession requireInProgressSession(Long sessionId) {
        return sessionRepository.findById(sessionId)
            .filter(session -> session.getStatus() == AiSimulationSessionStatus.IN_PROGRESS)
            .orElseThrow(this::notFound);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        return objectMapper.convertValue(value, Map.class);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private AiSimulationBusinessException notFound() {
        return new AiSimulationBusinessException(
            AiSimulationErrorCode.SIMULATION_NOT_FOUND);
    }
}
