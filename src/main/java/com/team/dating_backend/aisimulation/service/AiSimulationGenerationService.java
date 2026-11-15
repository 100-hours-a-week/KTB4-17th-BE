package com.team.dating_backend.aisimulation.service;

import com.team.dating_backend.aisimulation.client.AiSimulationAiClient;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationRequest;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiSimulationGenerationService {

    private static final int DEFAULT_TURNS = 10;

    private final AiSimulationPersistenceService persistenceService;
    private final AiSimulationAiClient aiClient;
    private final AiSimulationResponseValidator validator;
    private final AiSimulationEventPublisher publisher;

    @Transactional(propagation = Propagation.NEVER)
    public void generate(Long simulationId) {
        var state = persistenceService.loadGenerationState(simulationId);
        if (state.status() == AiSimulationSessionStatus.IN_PROGRESS) {
            var response = aiClient.run(new SimulationRequest(
                state.userId().toString(), state.targetUserId().toString(), DEFAULT_TURNS));
            validator.validate(response, state.userId(), state.targetUserId());
            state = persistenceService.completeGeneration(simulationId, response);
        }
        if (state.status() == AiSimulationSessionStatus.COMPLETED) {
            publisher.publishCompleted(state.simulationId(), state.finishedAt());
        }
    }
}
