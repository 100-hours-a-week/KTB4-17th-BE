package com.team.dating_backend.aisimulation.client;

import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationRequest;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationResponse;

public interface AiSimulationAiClient {

    SimulationResponse run(SimulationRequest request);
}
