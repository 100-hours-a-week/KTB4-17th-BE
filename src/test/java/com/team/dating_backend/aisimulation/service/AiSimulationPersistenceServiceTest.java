package com.team.dating_backend.aisimulation.service;

import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.SESSION_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.USER_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.aiResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.aisimulation.entity.AiSimulationMessage;
import com.team.dating_backend.aisimulation.entity.AiSimulationReport;
import com.team.dating_backend.aisimulation.entity.AiSimulationSession;
import com.team.dating_backend.aisimulation.enums.AiSimulationReportStatus;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import com.team.dating_backend.aisimulation.repository.AiSimulationMessageRepository;
import com.team.dating_backend.aisimulation.repository.AiSimulationReportRepository;
import com.team.dating_backend.aisimulation.repository.AiSimulationSessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class AiSimulationPersistenceServiceTest {

    private AiSimulationSessionRepository sessionRepository;
    private AiSimulationMessageRepository messageRepository;
    private AiSimulationReportRepository reportRepository;
    private AiSimulationPersistenceService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(AiSimulationSessionRepository.class);
        messageRepository = mock(AiSimulationMessageRepository.class);
        reportRepository = mock(AiSimulationReportRepository.class);
        service = new AiSimulationPersistenceService(
            sessionRepository, messageRepository, reportRepository, new ObjectMapper());
    }

    @Test
    void 시작할_때_진행중_세션과_생성중_리포트를_함께_저장한다() {
        given(sessionRepository.save(org.mockito.ArgumentMatchers.any()))
            .willAnswer(invocation -> invocation.getArgument(0));

        AiSimulationSession session = service.begin(USER_ID);

        assertThat(session.getUserId()).isEqualTo(USER_ID);
        assertThat(session.getStatus()).isEqualTo(AiSimulationSessionStatus.IN_PROGRESS);
        ArgumentCaptor<AiSimulationReport> reportCaptor = ArgumentCaptor.forClass(AiSimulationReport.class);
        verify(reportRepository).save(reportCaptor.capture());
        assertThat(reportCaptor.getValue().getStatus())
            .isEqualTo(AiSimulationReportStatus.GENERATING);
    }

    @Test
    void 성공하면_20개_메시지와_전체_결과를_저장하고_완료한다() {
        AiSimulationSession session = new AiSimulationSession(USER_ID, LocalDateTime.now());
        AiSimulationReport report = new AiSimulationReport(session, LocalDateTime.now());
        given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
        given(reportRepository.findBySessionId(SESSION_ID)).willReturn(Optional.of(report));

        service.complete(SESSION_ID, aiResponse());

        assertThat(session.getStatus()).isEqualTo(AiSimulationSessionStatus.COMPLETED);
        assertThat(report.getStatus()).isEqualTo(AiSimulationReportStatus.COMPLETED);
        assertThat(report.getOverallScore()).isEqualByComparingTo("78");
        assertThat(report.getSummary()).isEqualTo("대화가 잘 이어져요.");
        assertThat(report.getDetailResult()).containsEntry("simulation_id", "sim-001");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiSimulationMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(messageRepository).saveAll(messagesCaptor.capture());
        assertThat(messagesCaptor.getValue()).hasSize(20);
        assertThat(messagesCaptor.getValue().getFirst().getContent())
            .containsEntry("index", 0)
            .containsEntry("speaker", "a")
            .containsEntry("text", "대화 0");
    }

    @Test
    void 실패하면_세션과_기존_리포트만_실패_상태로_바꾼다() {
        AiSimulationSession session = new AiSimulationSession(USER_ID, LocalDateTime.now());
        AiSimulationReport report = new AiSimulationReport(session, LocalDateTime.now());
        given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
        given(reportRepository.findBySessionId(SESSION_ID)).willReturn(Optional.of(report));

        service.fail(SESSION_ID);

        assertThat(session.getStatus()).isEqualTo(AiSimulationSessionStatus.FAILED);
        assertThat(report.getStatus()).isEqualTo(AiSimulationReportStatus.FAILED);
        verify(messageRepository, org.mockito.Mockito.never())
            .saveAll(org.mockito.ArgumentMatchers.any());
    }
}
