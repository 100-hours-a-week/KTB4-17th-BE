package com.team.dating_backend.aisimulation.service;

import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.SESSION_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.TARGET_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.USER_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.aiResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.team.dating_backend.aisimulation.entity.AiSimulationMessage;
import com.team.dating_backend.aisimulation.dto.internal.AiSimulationExecutionDtos.GenerationInput;
import com.team.dating_backend.aisimulation.entity.AiSimulationReport;
import com.team.dating_backend.aisimulation.entity.AiSimulationSession;
import com.team.dating_backend.aisimulation.enums.AiSimulationReportStatus;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import com.team.dating_backend.aisimulation.repository.AiSimulationMessageRepository;
import com.team.dating_backend.aisimulation.repository.AiSimulationReportRepository;
import com.team.dating_backend.aisimulation.repository.AiSimulationSessionRepository;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.service.NotificationRequestService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import com.team.dating_backend.notification.exception.NotificationInvalidCreateRequestException;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class AiSimulationPersistenceServiceTest {

    private AiSimulationSessionRepository sessionRepository;
    private AiSimulationMessageRepository messageRepository;
    private AiSimulationReportRepository reportRepository;
    private NotificationRequestService notificationRequestService;
    private AiSimulationPersistenceService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(AiSimulationSessionRepository.class);
        messageRepository = mock(AiSimulationMessageRepository.class);
        reportRepository = mock(AiSimulationReportRepository.class);
        notificationRequestService = mock(NotificationRequestService.class);
        service = new AiSimulationPersistenceService(
            sessionRepository, messageRepository, reportRepository, new ObjectMapper(), notificationRequestService);
    }

    @Test
    void 시작할_때_진행중_세션과_생성중_리포트를_함께_저장한다() {
        given(sessionRepository.save(org.mockito.ArgumentMatchers.any()))
            .willAnswer(invocation -> invocation.getArgument(0));

        AiSimulationSession session = service.begin(USER_ID, TARGET_ID);

        assertThat(session.getUserId()).isEqualTo(USER_ID);
        assertThat(session.getTargetUserId()).isEqualTo(TARGET_ID);
        assertThat(session.getStatus()).isEqualTo(AiSimulationSessionStatus.IN_PROGRESS);
        ArgumentCaptor<AiSimulationReport> reportCaptor = ArgumentCaptor.forClass(AiSimulationReport.class);
        verify(reportRepository).save(reportCaptor.capture());
        assertThat(reportCaptor.getValue().getStatus())
            .isEqualTo(AiSimulationReportStatus.GENERATING);
        verifyNoInteractions(notificationRequestService);
    }

    @Test
    void 성공하면_20개_메시지와_전체_결과를_저장하고_완료한다() {
        AiSimulationSession session = new AiSimulationSession(USER_ID, TARGET_ID, LocalDateTime.now());
        ReflectionTestUtils.setField(session, "id", SESSION_ID);
        AiSimulationReport report = new AiSimulationReport(session, LocalDateTime.now());
        given(sessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(session));
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
        var order = inOrder(sessionRepository, reportRepository, messageRepository);
        order.verify(sessionRepository).findByIdForUpdate(SESSION_ID);
        order.verify(reportRepository).findBySessionId(SESSION_ID);
        order.verify(messageRepository).saveAll(org.mockito.ArgumentMatchers.any());
        assertNotificationRequest();
        verifyNoMoreInteractions(notificationRequestService);
    }

    @Test
    void 완료_알림_요청_등록_실패를_숨기지_않고_원래_예외를_전달한다() {
        AiSimulationSession session = new AiSimulationSession(USER_ID, TARGET_ID, LocalDateTime.now());
        ReflectionTestUtils.setField(session, "id", SESSION_ID);
        AiSimulationReport report = new AiSimulationReport(session, LocalDateTime.now());
        given(sessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(session));
        given(reportRepository.findBySessionId(SESSION_ID)).willReturn(Optional.of(report));
        var failure = new NotificationInvalidCreateRequestException();
        willThrow(failure).given(notificationRequestService).requestNotification(any());

        assertThatThrownBy(() -> service.complete(SESSION_ID, aiResponse())).isSameAs(failure);
    }

    @Test
    void 실패하면_세션과_기존_리포트만_실패_상태로_바꾼다() {
        AiSimulationSession session = new AiSimulationSession(USER_ID, TARGET_ID, LocalDateTime.now());
        AiSimulationReport report = new AiSimulationReport(session, LocalDateTime.now());
        given(sessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(session));
        given(reportRepository.findBySessionId(SESSION_ID)).willReturn(Optional.of(report));

        service.fail(SESSION_ID);

        assertThat(session.getStatus()).isEqualTo(AiSimulationSessionStatus.FAILED);
        assertThat(report.getStatus()).isEqualTo(AiSimulationReportStatus.FAILED);
        verify(messageRepository, org.mockito.Mockito.never())
            .saveAll(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(notificationRequestService);
    }

    @ParameterizedTest
    @EnumSource(value = AiSimulationSessionStatus.class, names = {"COMPLETED", "FAILED", "CANCELED"})
    void 종료된_세션에는_완료_결과를_저장하지_않는다(AiSimulationSessionStatus status) {
        AiSimulationSession session = endedSession(status);
        LocalDateTime finishedAt = session.getFinishedAt();
        given(sessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(session));

        assertThatThrownBy(() -> service.complete(SESSION_ID, aiResponse()))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.SIMULATION_NOT_IN_PROGRESS));

        assertThat(session.getStatus()).isEqualTo(status);
        assertThat(session.getFinishedAt()).isEqualTo(finishedAt);
        verifyNoInteractions(reportRepository, messageRepository, notificationRequestService);
    }

    @ParameterizedTest
    @EnumSource(value = AiSimulationSessionStatus.class, names = {"COMPLETED", "FAILED", "CANCELED"})
    void 종료된_세션에_실패_처리가_와도_기존_상태와_결과를_유지한다(
        AiSimulationSessionStatus status) {
        AiSimulationSession session = endedSession(status);
        LocalDateTime finishedAt = session.getFinishedAt();
        given(sessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(session));

        service.fail(SESSION_ID);

        assertThat(session.getStatus()).isEqualTo(status);
        assertThat(session.getFinishedAt()).isEqualTo(finishedAt);
        verifyNoInteractions(reportRepository, messageRepository);
    }

    @Test
    void 완료_저장을_두번_호출해도_메시지를_중복_저장하지_않는다() {
        AiSimulationSession session = new AiSimulationSession(USER_ID, TARGET_ID, LocalDateTime.now());
        ReflectionTestUtils.setField(session, "id", SESSION_ID);
        AiSimulationReport report = new AiSimulationReport(session, LocalDateTime.now());
        given(sessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(session));
        given(reportRepository.findBySessionId(SESSION_ID)).willReturn(Optional.of(report));
        service.complete(SESSION_ID, aiResponse());
        LocalDateTime finishedAt = session.getFinishedAt();

        assertThatThrownBy(() -> service.complete(SESSION_ID, aiResponse()))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.SIMULATION_NOT_IN_PROGRESS));

        assertThat(session.getStatus()).isEqualTo(AiSimulationSessionStatus.COMPLETED);
        assertThat(session.getFinishedAt()).isEqualTo(finishedAt);
        assertThat(report.getStatus()).isEqualTo(AiSimulationReportStatus.COMPLETED);
        verify(messageRepository, times(1)).saveAll(org.mockito.ArgumentMatchers.any());
        verify(reportRepository, times(1)).findBySessionId(SESSION_ID);
        assertNotificationRequest();
        verifyNoMoreInteractions(notificationRequestService);
    }

    @Test
    void 실패_처리를_두번_호출해도_최초_종료_시간을_유지한다() {
        AiSimulationSession session = new AiSimulationSession(USER_ID, TARGET_ID, LocalDateTime.now());
        AiSimulationReport report = new AiSimulationReport(session, LocalDateTime.now());
        given(sessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(session));
        given(reportRepository.findBySessionId(SESSION_ID)).willReturn(Optional.of(report));
        service.fail(SESSION_ID);
        LocalDateTime finishedAt = session.getFinishedAt();

        service.fail(SESSION_ID);

        assertThat(session.getStatus()).isEqualTo(AiSimulationSessionStatus.FAILED);
        assertThat(session.getFinishedAt()).isEqualTo(finishedAt);
        assertThat(report.getStatus()).isEqualTo(AiSimulationReportStatus.FAILED);
        verify(reportRepository, times(1)).findBySessionId(SESSION_ID);
        verifyNoInteractions(messageRepository);
    }

    @Test
    void 없는_세션의_완료_저장은_조회_오류를_반환한다() {
        given(sessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete(SESSION_ID, aiResponse()))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.SIMULATION_NOT_FOUND));

        verifyNoInteractions(reportRepository, messageRepository);
    }

    @Test
    void 없는_세션의_실패_처리는_아무것도_저장하지_않는다() {
        given(sessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.empty());

        service.fail(SESSION_ID);

        verifyNoInteractions(reportRepository, messageRepository);
    }

    @Test
    void 완료_저장할_리포트가_없으면_세션과_메시지를_변경하지_않는다() {
        AiSimulationSession session = new AiSimulationSession(USER_ID, TARGET_ID, LocalDateTime.now());
        given(sessionRepository.findByIdForUpdate(SESSION_ID)).willReturn(Optional.of(session));
        given(reportRepository.findBySessionId(SESSION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete(SESSION_ID, aiResponse()))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.SIMULATION_NOT_FOUND));

        assertThat(session.getStatus()).isEqualTo(AiSimulationSessionStatus.IN_PROGRESS);
        assertThat(session.getFinishedAt()).isNull();
        verifyNoInteractions(messageRepository);
    }

    @Test
    void 진행중_세션에서_최초_생성_방향의_ID만_읽는다() {
        AiSimulationSession session = new AiSimulationSession(TARGET_ID, USER_ID, LocalDateTime.now());
        ReflectionTestUtils.setField(session, "id", SESSION_ID);
        given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));

        assertThat(service.loadGenerationInput(SESSION_ID))
            .contains(new GenerationInput(SESSION_ID, TARGET_ID, USER_ID));
        verifyNoInteractions(reportRepository, messageRepository);
    }

    @ParameterizedTest
    @EnumSource(value = AiSimulationSessionStatus.class, names = {"COMPLETED", "FAILED", "CANCELED"})
    void 종료된_세션에는_AI_실행_입력을_반환하지_않는다(AiSimulationSessionStatus status) {
        given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(endedSession(status)));

        assertThat(service.loadGenerationInput(SESSION_ID)).isEmpty();
        verifyNoInteractions(reportRepository, messageRepository);
    }

    @Test
    void 없는_세션에는_AI_실행_입력을_반환하지_않는다() {
        given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.empty());

        assertThat(service.loadGenerationInput(SESSION_ID)).isEmpty();
        verifyNoInteractions(reportRepository, messageRepository);
    }

    @Test
    void 상대_ID가_없는_기존_세션의_AI_실행_입력은_거부한다() {
        AiSimulationSession session = new AiSimulationSession(USER_ID, TARGET_ID, LocalDateTime.now());
        ReflectionTestUtils.setField(session, "targetUserId", null);
        given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));

        assertThatThrownBy(() -> service.loadGenerationInput(SESSION_ID))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.SIMULATION_TARGET_USER_MISSING));
        verifyNoInteractions(reportRepository, messageRepository);
    }

    private void assertNotificationRequest() {
        var captor = ArgumentCaptor.forClass(NotificationCreateRequested.class);
        verify(notificationRequestService).requestNotification(captor.capture());
        var request = captor.getValue();
        assertThat(request.requestedAt()).isNotNull();
        assertThat(request).isEqualTo(new NotificationCreateRequested(1, "AI_SIMULATION_COMPLETED:" + SESSION_ID,
            USER_ID, NotificationType.AI_SIMULATION_REPORT_COMPLETED, "시뮬레이션 완료",
            "두 사람의 대화 시뮬레이션과 분석 보고서가 준비됐어요.", "AI_SIMULATION", SESSION_ID, request.requestedAt()));
    }

    private AiSimulationSession endedSession(AiSimulationSessionStatus status) {
        AiSimulationSession session = new AiSimulationSession(USER_ID, TARGET_ID, LocalDateTime.now());
        ReflectionTestUtils.setField(session, "status", status);
        ReflectionTestUtils.setField(session, "finishedAt", LocalDateTime.of(2026, 10, 1, 0, 0));
        return session;
    }
}
