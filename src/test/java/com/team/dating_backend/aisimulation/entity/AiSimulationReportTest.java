package com.team.dating_backend.aisimulation.entity;

import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.CREATED_AT;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.TARGET_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.enums.AiSimulationReportStatus;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AiSimulationReportTest {

    @ParameterizedTest
    @EnumSource(value = AiSimulationReportStatus.class, names = {"COMPLETED", "FAILED"})
    void 종료된_리포트의_완료는_오류_코드로_거부하고_기존_결과를_유지한다(AiSimulationReportStatus status) {
        AiSimulationSession session = new AiSimulationSession(USER_ID, TARGET_ID, CREATED_AT);
        AiSimulationReport report = new AiSimulationReport(session, CREATED_AT);
        if (status == AiSimulationReportStatus.COMPLETED) {
            report.complete(78, "기존 결과", Map.of("simulation_id", "original"), CREATED_AT.plusMinutes(1));
        } else {
            report.fail();
        }
        var score = report.getOverallScore();
        var summary = report.getSummary();
        var detail = report.getDetailResult();
        var completedAt = report.getCompletedAt();

        assertThatThrownBy(() -> report.complete(
            90, "새 결과", Map.of("simulation_id", "new"), CREATED_AT.plusMinutes(2)))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.SIMULATION_REPORT_NOT_GENERATING));

        assertThat(report.getStatus()).isEqualTo(status);
        assertThat(report.getOverallScore()).isEqualTo(score);
        assertThat(report.getSummary()).isEqualTo(summary);
        assertThat(report.getDetailResult()).isEqualTo(detail);
        assertThat(report.getCompletedAt()).isEqualTo(completedAt);
    }
}
