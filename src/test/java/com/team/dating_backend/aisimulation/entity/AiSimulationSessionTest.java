package com.team.dating_backend.aisimulation.entity;

import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.CREATED_AT;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.USER_ID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AiSimulationSessionTest {

    @Test
    void 요청자가_없으면_새_세션을_생성할_수_없다() {
        assertThatThrownBy(() -> new AiSimulationSession(null, USER_ID, CREATED_AT))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("userId must not be null");
    }

    @Test
    void 상대방이_없으면_새_세션을_생성할_수_없다() {
        assertThatThrownBy(() -> new AiSimulationSession(USER_ID, null, CREATED_AT))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("targetUserId must not be null");
    }

    @Test
    void 자기_자신과의_새_세션을_생성할_수_없다() {
        assertThatThrownBy(() -> new AiSimulationSession(USER_ID, USER_ID, CREATED_AT))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
