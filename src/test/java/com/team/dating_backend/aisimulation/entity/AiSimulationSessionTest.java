package com.team.dating_backend.aisimulation.entity;

import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.CREATED_AT;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.TARGET_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class AiSimulationSessionTest {

    @Test
    void 요청자가_없으면_새_세션을_생성할_수_없다() {
        assertThatThrownBy(() -> new AiSimulationSession(null, USER_ID, CREATED_AT))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.INVALID_TARGET_USER));
    }

    @Test
    void 상대방이_없으면_새_세션을_생성할_수_없다() {
        assertThatThrownBy(() -> new AiSimulationSession(USER_ID, null, CREATED_AT))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.INVALID_TARGET_USER));
    }

    @Test
    void 자기_자신과의_새_세션을_생성할_수_없다() {
        assertThatThrownBy(() -> new AiSimulationSession(USER_ID, USER_ID, CREATED_AT))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.INVALID_TARGET_USER));
    }

    @ParameterizedTest
    @EnumSource(value = AiSimulationSessionStatus.class, names = {"COMPLETED", "FAILED", "CANCELED"})
    void 종료된_세션의_완료는_오류_코드로_거부하고_기존_상태를_유지한다(AiSimulationSessionStatus status) {
        AiSimulationSession session = new AiSimulationSession(USER_ID, TARGET_ID, CREATED_AT);
        LocalDateTime finishedAt = CREATED_AT.plusMinutes(1);
        ReflectionTestUtils.setField(session, "status", status);
        ReflectionTestUtils.setField(session, "finishedAt", finishedAt);

        assertThatThrownBy(() -> session.complete(finishedAt.plusMinutes(1)))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.SIMULATION_NOT_IN_PROGRESS));

        assertThat(session.getStatus()).isEqualTo(status);
        assertThat(session.getFinishedAt()).isEqualTo(finishedAt);
    }
}
