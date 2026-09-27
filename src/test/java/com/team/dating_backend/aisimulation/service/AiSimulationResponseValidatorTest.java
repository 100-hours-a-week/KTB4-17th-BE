package com.team.dating_backend.aisimulation.service;

import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.TARGET_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.USER_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.aiResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiSimulationResponseValidatorTest {

    private final AiSimulationResponseValidator validator = new AiSimulationResponseValidator();

    @Test
    void 정상적인_20개_교대_발화와_리포트를_허용한다() {
        assertThatCode(() -> validator.validate(aiResponse(), USER_ID, TARGET_ID))
            .doesNotThrowAnyException();
    }

    @Test
    void 발화가_20개보다_적으면_거절한다() {
        AiSimulationAiPayloads.SimulationResponse original = aiResponse();
        AiSimulationAiPayloads.SimulationResponse invalid = new AiSimulationAiPayloads.SimulationResponse(
            original.simulationId(),
            original.me(),
            original.partner(),
            original.turns(),
            List.of(original.transcript().getFirst()),
            original.report(),
            original.createdAt());

        assertInvalid(invalid);
    }

    @Test
    void 상대_user_id가_요청과_다르면_거절한다() {
        AiSimulationAiPayloads.SimulationResponse original = aiResponse();
        AiSimulationAiPayloads.PersonaBrief wrongPartner = new AiSimulationAiPayloads.PersonaBrief(
            original.partner().personaId(),
            "999",
            original.partner().nickname(),
            original.partner().version(),
            original.partner().headline(),
            original.partner().accuracy());
        AiSimulationAiPayloads.SimulationResponse invalid = new AiSimulationAiPayloads.SimulationResponse(
            original.simulationId(),
            original.me(),
            wrongPartner,
            original.turns(),
            original.transcript(),
            original.report(),
            original.createdAt());

        assertInvalid(invalid);
    }

    private void assertInvalid(AiSimulationAiPayloads.SimulationResponse response) {
        assertThatThrownBy(() -> validator.validate(response, USER_ID, TARGET_ID))
            .isInstanceOfSatisfying(
                AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.AI_SERVER_RESPONSE_INVALID));
    }
}
