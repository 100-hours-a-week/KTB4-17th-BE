package com.team.dating_backend.aisimulation.service;

import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.CREATED_AT;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.SESSION_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.USER_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.aiResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.team.dating_backend.aisimulation.entity.AiSimulationMessage;
import com.team.dating_backend.aisimulation.entity.AiSimulationReport;
import com.team.dating_backend.aisimulation.entity.AiSimulationSession;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.enums.AiSimulationReportStatus;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import com.team.dating_backend.aisimulation.repository.AiSimulationMessageRepository;
import com.team.dating_backend.aisimulation.repository.AiSimulationReportRepository;
import com.team.dating_backend.aisimulation.repository.AiSimulationSessionRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.ObjectMapper;

class AiSimulationQueryServiceTest {

    private AiSimulationSessionRepository sessionRepository;
    private AiSimulationMessageRepository messageRepository;
    private AiSimulationReportRepository reportRepository;
    private AiSimulationQueryService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(AiSimulationSessionRepository.class);
        messageRepository = mock(AiSimulationMessageRepository.class);
        reportRepository = mock(AiSimulationReportRepository.class);
        objectMapper = new ObjectMapper();
        service = new AiSimulationQueryService(
            sessionRepository,
            messageRepository,
            reportRepository,
            new AiSimulationResponseMapper(),
            new AiSimulationResponseValidator(),
            objectMapper);
    }

    @Test
    void 소유자는_저장된_메시지와_리포트를_조회한다() {
        AiSimulationSession session = session();
        AiSimulationReport report = report();
        given(sessionRepository.findByIdAndUserIdAndStatus(
            SESSION_ID, USER_ID, AiSimulationSessionStatus.COMPLETED))
            .willReturn(Optional.of(session));
        given(reportRepository.findBySessionIdAndDeletedAtIsNull(SESSION_ID))
            .willReturn(Optional.of(report));
        List<AiSimulationMessage> messages = messages();
        given(messageRepository.findBySessionIdOrderByIdAsc(SESSION_ID))
            .willReturn(messages);

        var result = service.get(USER_ID, SESSION_ID);

        assertThat(result.simulationId()).isEqualTo(SESSION_ID);
        assertThat(result.transcript()).hasSize(20);
        assertThat(result.report().overall().score()).isEqualTo(78);
    }

    @Test
    void 다른_사용자의_결과는_존재하지_않는_것처럼_처리한다() {
        given(sessionRepository.findByIdAndUserIdAndStatus(
            SESSION_ID, 999L, AiSimulationSessionStatus.COMPLETED))
            .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(999L, SESSION_ID))
            .isInstanceOfSatisfying(
                AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.SIMULATION_NOT_FOUND));
    }

    @Test
    void 목록에는_로그인_사용자의_완료_세션만_요약한다() {
        AiSimulationSession session = session();
        AiSimulationReport report = report();
        given(sessionRepository.findByUserIdAndStatusOrderByIdDesc(
            USER_ID, AiSimulationSessionStatus.COMPLETED, PageRequest.of(0, 20)))
            .willReturn(List.of(session));
        given(reportRepository.findBySessionIdAndDeletedAtIsNull(SESSION_ID))
            .willReturn(Optional.of(report));

        var result = service.list(USER_ID);

        assertThat(result).singleElement().satisfies(summary -> {
            assertThat(summary.simulationId()).isEqualTo(SESSION_ID);
            assertThat(summary.partner().userId()).isEqualTo(8L);
            assertThat(summary.overallScore()).isEqualTo(78);
        });
    }

    private AiSimulationSession session() {
        AiSimulationSession session = mock(AiSimulationSession.class);
        given(session.getId()).willReturn(SESSION_ID);
        given(session.getUserId()).willReturn(USER_ID);
        given(session.getCreatedAt()).willReturn(CREATED_AT);
        return session;
    }

    private AiSimulationReport report() {
        AiSimulationReport report = mock(AiSimulationReport.class);
        given(report.getStatus()).willReturn(AiSimulationReportStatus.COMPLETED);
        given(report.getDetailResult()).willReturn(toMap(aiResponse()));
        return report;
    }

    private List<AiSimulationMessage> messages() {
        return aiResponse().transcript().stream().map(turn -> {
            AiSimulationMessage message = mock(AiSimulationMessage.class);
            given(message.getContent()).willReturn(toMap(turn));
            return message;
        }).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        return objectMapper.convertValue(value, Map.class);
    }
}
