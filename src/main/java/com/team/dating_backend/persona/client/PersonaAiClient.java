package com.team.dating_backend.persona.client;

import com.team.dating_backend.persona.dto.ai.PersonaAiPayloads.AnswerRequest;
import com.team.dating_backend.persona.dto.ai.PersonaAiPayloads.ConfirmRequest;
import com.team.dating_backend.persona.dto.ai.PersonaAiPayloads.ConfirmResponse;
import com.team.dating_backend.persona.dto.ai.PersonaAiPayloads.PersonaResponse;
import com.team.dating_backend.persona.dto.ai.PersonaAiPayloads.StartRequest;
import com.team.dating_backend.persona.dto.ai.PersonaAiPayloads.TurnResponse;

public interface PersonaAiClient {

    TurnResponse start(StartRequest request);

    TurnResponse answer(String sessionId, AnswerRequest request);

    TurnResponse skip(String sessionId);

    TurnResponse finish(String sessionId);

    PersonaResponse build(String sessionId);

    ConfirmResponse confirm(String personaId, ConfirmRequest request);
}
