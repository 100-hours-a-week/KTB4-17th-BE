package com.team.dating_backend.aisimulation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class AiSimulationEventFactoryTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 11, 6, 1, 2, 3, 123_456_000);
    private static final LocalDateTime FINISHED_AT = CREATED_AT.plusSeconds(50);

    private final AiSimulationEventFactory factory = new AiSimulationEventFactory();

    @Test
    void 요청을_다시_구성해도_사건_ID와_저장된_UTC_생성_시각을_유지한다() {
        var event = factory.requested(10L, CREATED_AT);

        assertThat(event.eventId()).isEqualTo("ai-simulation:10:requested");
        assertThat(event.simulationId()).isEqualTo(10L);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-11-06T01:02:03.123456Z"));
        assertThat(factory.requested(10L, CREATED_AT)).isEqualTo(event);
    }

    @Test
    void 완료를_다시_구성해도_요청과_다른_사건_ID와_저장된_완료_시각을_유지한다() {
        var event = factory.completed(10L, FINISHED_AT);

        assertThat(event.eventId()).isEqualTo("ai-simulation:10:completed");
        assertThat(event.simulationId()).isEqualTo(10L);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-11-06T01:02:53.123456Z"));
        assertThat(factory.completed(10L, FINISHED_AT)).isEqualTo(event);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void 저장된_세션_ID가_없으면_이벤트를_만들지_않는다(Long simulationId) {
        assertInvalidEvent(() -> factory.requested(simulationId, CREATED_AT));
        assertInvalidEvent(() -> factory.completed(simulationId, FINISHED_AT));
    }

    @Test
    void 저장된_시각이_없으면_현재_시각으로_대체하지_않는다() {
        assertInvalidEvent(() -> factory.requested(10L, null));
        assertInvalidEvent(() -> factory.completed(10L, null));
    }

    private void assertInvalidEvent(Runnable action) {
        try {
            action.run();
        } catch (AiSimulationBusinessException exception) {
            assertThat(exception.getErrorCode()).isEqualTo(AiSimulationErrorCode.SIMULATION_EVENT_INVALID);
            return;
        }
        throw new AssertionError("Expected SIMULATION_EVENT_INVALID");
    }
}
