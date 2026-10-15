package com.team.dating_backend.aisimulation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.team.dating_backend.aisimulation.entity.AiSimulationReport;
import com.team.dating_backend.aisimulation.entity.AiSimulationSession;
import com.team.dating_backend.aisimulation.enums.AiSimulationReportStatus;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import com.team.dating_backend.aisimulation.repository.AiSimulationReportRepository;
import com.team.dating_backend.aisimulation.repository.AiSimulationSessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AiSimulationWithdrawalTerminationServiceTest {

    private static final Long USER_ID = 1L;
    private static final LocalDateTime ENDED_AT = LocalDateTime.of(2026, 10, 15, 12, 0);

    private AiSimulationSessionRepository sessionRepository;
    private AiSimulationReportRepository reportRepository;
    private AiSimulationWithdrawalTerminationService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(AiSimulationSessionRepository.class);
        reportRepository = mock(AiSimulationReportRepository.class);
        service = new AiSimulationWithdrawalTerminationService(sessionRepository, reportRepository);
    }

    @Test
    void 사용자가_탈퇴하면_실행자나_상대방으로_참여한_진행_중_시뮬레이션을_취소하고_생성_중인_리포트를_실패_처리한다() {
        AiSimulationSession ownedSession = session(10L, USER_ID, 2L);
        AiSimulationSession targetedSession = session(11L, 2L, USER_ID);
        AiSimulationReport ownedReport = new AiSimulationReport(ownedSession, ENDED_AT.minusHours(1));
        AiSimulationReport targetedReport = new AiSimulationReport(targetedSession, ENDED_AT.minusHours(1));
        given(sessionRepository.findAllForUserByStatusForUpdate(
            USER_ID, AiSimulationSessionStatus.IN_PROGRESS))
            .willReturn(List.of(ownedSession, targetedSession));
        given(reportRepository.findBySessionId(10L)).willReturn(Optional.of(ownedReport));
        given(reportRepository.findBySessionId(11L)).willReturn(Optional.of(targetedReport));

        service.terminateForUser(USER_ID, ENDED_AT);

        assertThat(List.of(ownedSession, targetedSession)).allSatisfy(session -> {
            assertThat(session.getStatus()).isEqualTo(AiSimulationSessionStatus.CANCELED);
            assertThat(session.getFinishedAt()).isEqualTo(ENDED_AT);
        });
        assertThat(List.of(ownedReport, targetedReport))
            .allSatisfy(report -> assertThat(report.getStatus()).isEqualTo(AiSimulationReportStatus.FAILED));
    }

    private AiSimulationSession session(Long id, Long userId, Long targetUserId) {
        AiSimulationSession session = new AiSimulationSession(
            userId, targetUserId, ENDED_AT.minusHours(1));
        ReflectionTestUtils.setField(session, "id", id);
        return session;
    }
}
