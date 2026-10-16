package com.team.dating_backend.aisimulation.service;

import com.team.dating_backend.aisimulation.client.AiSimulationAiClient;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationRequest;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationResponse;
import com.team.dating_backend.aisimulation.dto.internal.AiSimulationExecutionDtos.GenerationInput;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiSimulationWorker {

    private static final int DEFAULT_TURNS = 10;

    private final AiSimulationPersistenceService persistenceService;
    private final AiSimulationAiClient aiClient;
    private final AiSimulationResponseValidator validator;

    @Async("aiSimulationExecutor")
    public void generate(Long simulationId) {
        try {
            Optional<GenerationInput> input = persistenceService.loadGenerationInput(simulationId);
            if (input.isEmpty()) {
                return;
            }

            GenerationInput generation = input.get();
            SimulationResponse response = aiClient.run(new SimulationRequest(
                generation.userId().toString(), generation.targetUserId().toString(), DEFAULT_TURNS));
            validator.validate(response, generation.userId(), generation.targetUserId());
            persistenceService.complete(simulationId, response);
        } catch (RuntimeException exception) {
            log.warn("AI simulation generation failed. simulationId={}, exceptionType={}",
                simulationId, exception.getClass().getSimpleName());
            try {
                persistenceService.fail(simulationId);
            } catch (RuntimeException persistenceException) {
                log.error("AI simulation failure persistence failed. simulationId={}, exceptionType={}",
                    simulationId, persistenceException.getClass().getSimpleName());
            }
        }
    }
}
