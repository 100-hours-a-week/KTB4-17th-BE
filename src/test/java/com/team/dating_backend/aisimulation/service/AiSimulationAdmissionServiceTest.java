package com.team.dating_backend.aisimulation.service;

import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.CREATED_AT;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.SESSION_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.TARGET_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.aisimulation.dto.internal.AiSimulationExecutionDtos.AdmissionDecision;
import com.team.dating_backend.aisimulation.entity.AiSimulationSession;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import com.team.dating_backend.aisimulation.repository.AiSimulationSessionRepository;
import com.team.dating_backend.common.lock.repository.UserPairLockRepository;
import com.team.dating_backend.security.AuthenticationRequiredException;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserBlockRepository;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

class AiSimulationAdmissionServiceTest {

    private UserRepository userRepository;
    private UserBlockRepository userBlockRepository;
    private UserPairLockRepository userPairLockRepository;
    private AiSimulationSessionRepository sessionRepository;
    private AiSimulationPersistenceService persistenceService;
    private AiSimulationAdmissionService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userBlockRepository = mock(UserBlockRepository.class);
        userPairLockRepository = mock(UserPairLockRepository.class);
        sessionRepository = mock(AiSimulationSessionRepository.class);
        persistenceService = mock(AiSimulationPersistenceService.class);
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(user(UserStatus.ACTIVE)));
        given(userRepository.findByIdForUpdate(TARGET_ID)).willReturn(Optional.of(user(UserStatus.ACTIVE)));
        service = new AiSimulationAdmissionService(
            new AiSimulationTargetValidator(userRepository, userBlockRepository), userPairLockRepository,
            sessionRepository,
            persistenceService);
    }

    @ParameterizedTest
    @CsvSource({"7, 8", "8, 7"})
    void 요청_방향과_무관하게_같은_조합을_잠그고_최초_세션만_생성한다(
        Long userId, Long targetUserId) {
        AiSimulationSession session = new AiSimulationSession(userId, targetUserId, CREATED_AT);
        ReflectionTestUtils.setField(session, "id", SESSION_ID);
        given(sessionRepository.findForReuseByUserPair(
            userId, targetUserId, PageRequest.of(0, 1))).willReturn(List.of());
        given(persistenceService.begin(userId, targetUserId)).willReturn(session);

        AdmissionDecision result = service.admit(userId, targetUserId);

        assertThat(result).isEqualTo(new AdmissionDecision(
            SESSION_ID, AiSimulationSessionStatus.IN_PROGRESS, true));
        assertThat(session.getUserId()).isEqualTo(userId);
        assertThat(session.getTargetUserId()).isEqualTo(targetUserId);
        var order = inOrder(userPairLockRepository, sessionRepository, persistenceService);
        order.verify(userPairLockRepository).acquire(userId, targetUserId);
        order.verify(sessionRepository).findForReuseByUserPair(
            userId, targetUserId, PageRequest.of(0, 1));
        order.verify(persistenceService).begin(userId, targetUserId);
    }

    @ParameterizedTest
    @EnumSource(AiSimulationSessionStatus.class)
    void 기존_세션은_상태와_요청_방향에_관계없이_재사용하고_새로_생성하지_않는다(
        AiSimulationSessionStatus status) {
        AiSimulationSession session = existingSession(SESSION_ID, status);
        given(sessionRepository.findForReuseByUserPair(
            USER_ID, TARGET_ID, PageRequest.of(0, 1))).willReturn(List.of(session));
        given(sessionRepository.findForReuseByUserPair(
            TARGET_ID, USER_ID, PageRequest.of(0, 1))).willReturn(List.of(session));

        AdmissionDecision original = service.admit(USER_ID, TARGET_ID);
        AdmissionDecision reversed = service.admit(TARGET_ID, USER_ID);

        assertThat(original).isEqualTo(new AdmissionDecision(SESSION_ID, status, false));
        assertThat(reversed).isEqualTo(original);
        assertThat(session.getUserId()).isEqualTo(USER_ID);
        assertThat(session.getTargetUserId()).isEqualTo(TARGET_ID);
        assertThat(session.getStatus()).isEqualTo(status);
        verify(userPairLockRepository).acquire(USER_ID, TARGET_ID);
        verify(userPairLockRepository).acquire(TARGET_ID, USER_ID);
        verifyNoInteractions(persistenceService);
    }

    @Test
    void 자기_자신과의_접수는_잠금이나_저장_없이_거부한다() {
        assertThatThrownBy(() -> service.admit(USER_ID, USER_ID))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.INVALID_TARGET_USER));

        verifyNoInteractions(userRepository, userPairLockRepository, sessionRepository, persistenceService);
    }

    @Test
    void 존재하지_않는_상대방은_세션을_조회하거나_저장하지_않는다() {
        given(userRepository.findByIdForUpdate(TARGET_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.admit(USER_ID, TARGET_ID))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.TARGET_USER_NOT_FOUND));

        verify(userPairLockRepository).acquire(USER_ID, TARGET_ID);
        verifyNoInteractions(sessionRepository, persistenceService);
    }

    @ParameterizedTest
    @CsvSource({"7, 8", "8, 7"})
    void 활성_차단_관계가_있으면_기존_결과_조회와_새_접수를_막는다(Long userId, Long targetUserId) {
        given(userBlockRepository.existsActiveBlockBetween(userId, targetUserId)).willReturn(true);

        assertThatThrownBy(() -> service.admit(userId, targetUserId))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.TARGET_USER_NOT_FOUND));

        verify(userPairLockRepository).acquire(userId, targetUserId);
        verifyNoInteractions(sessionRepository, persistenceService);
    }

    @ParameterizedTest
    @CsvSource({"7, 8", "8, 7"})
    void 최신_실패의_재시도는_원래_참여자_순서로_새_세션을_만든다(Long userId, Long targetUserId) {
        AiSimulationSession failed = existingSession(SESSION_ID, AiSimulationSessionStatus.FAILED);
        AiSimulationSession next = existingSession(SESSION_ID + 1, AiSimulationSessionStatus.IN_PROGRESS);
        given(sessionRepository.findPairByIdAndParticipant(SESSION_ID, userId))
            .willReturn(java.util.Optional.of(pair()));
        given(sessionRepository.findLatestByUserPair(userId, targetUserId, PageRequest.of(0, 1)))
            .willReturn(List.of(failed));
        given(persistenceService.begin(USER_ID, TARGET_ID)).willReturn(next);

        AdmissionDecision result = service.admitAttempt(userId, SESSION_ID);

        assertThat(result).isEqualTo(new AdmissionDecision(next.getId(), AiSimulationSessionStatus.IN_PROGRESS, true));
        assertThat(failed.getStatus()).isEqualTo(AiSimulationSessionStatus.FAILED);
        var order = inOrder(userPairLockRepository, sessionRepository, persistenceService);
        order.verify(userPairLockRepository).acquire(userId, targetUserId);
        order.verify(sessionRepository).findLatestByUserPair(userId, targetUserId, PageRequest.of(0, 1));
        order.verify(persistenceService).begin(USER_ID, TARGET_ID);
    }

    @ParameterizedTest
    @EnumSource(AiSimulationSessionStatus.class)
    void 이전_ID로_반복된_시도는_새_세션의_상태와_관계없이_최신_세션을_반환한다(AiSimulationSessionStatus status) {
        given(sessionRepository.findPairByIdAndParticipant(SESSION_ID, USER_ID))
            .willReturn(java.util.Optional.of(pair()));
        given(sessionRepository.findLatestByUserPair(USER_ID, TARGET_ID, PageRequest.of(0, 1)))
            .willReturn(List.of(existingSession(SESSION_ID + 1, status)));

        assertThat(service.admitAttempt(USER_ID, SESSION_ID))
            .isEqualTo(new AdmissionDecision(SESSION_ID + 1, status, false));
        verifyNoInteractions(persistenceService);
    }

    @ParameterizedTest
    @EnumSource(value = AiSimulationSessionStatus.class, names = {"IN_PROGRESS", "COMPLETED", "CANCELED"})
    void 최신_세션이_실패가_아니면_새_시도를_만들지_않는다(AiSimulationSessionStatus status) {
        given(sessionRepository.findPairByIdAndParticipant(SESSION_ID, USER_ID))
            .willReturn(java.util.Optional.of(pair()));
        given(sessionRepository.findLatestByUserPair(USER_ID, TARGET_ID, PageRequest.of(0, 1)))
            .willReturn(List.of(existingSession(SESSION_ID, status)));

        assertThat(service.admitAttempt(USER_ID, SESSION_ID))
            .isEqualTo(new AdmissionDecision(SESSION_ID, status, false));
        verifyNoInteractions(persistenceService);
    }

    @Test
    void 참여자가_아니면_재시도_잠금과_저장에_접근하지_않는다() {
        assertThatThrownBy(() -> service.admitAttempt(99L, SESSION_ID))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.SIMULATION_NOT_FOUND));
        verifyNoInteractions(userRepository, userPairLockRepository, persistenceService);
    }

    @Test
    void 상대방이_없는_레거시_세션은_재시도하지_않는다() {
        var pair = mock(AiSimulationSessionRepository.ParticipantPair.class);
        given(pair.getUserId()).willReturn(USER_ID);
        given(pair.getTargetUserId()).willReturn(null);
        given(sessionRepository.findPairByIdAndParticipant(SESSION_ID, USER_ID))
            .willReturn(java.util.Optional.of(pair));

        assertThatThrownBy(() -> service.admitAttempt(USER_ID, SESSION_ID))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.SIMULATION_NOT_FOUND));
        verifyNoInteractions(userRepository, userPairLockRepository, persistenceService);
    }

    @Test
    void 차단된_상대와의_재시도는_거부한다() {
        given(sessionRepository.findPairByIdAndParticipant(SESSION_ID, USER_ID))
            .willReturn(java.util.Optional.of(pair()));
        given(userBlockRepository.existsActiveBlockBetween(USER_ID, TARGET_ID)).willReturn(true);

        assertThatThrownBy(() -> service.admitAttempt(USER_ID, SESSION_ID))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.TARGET_USER_NOT_FOUND));
        verify(userPairLockRepository).acquire(USER_ID, TARGET_ID);
        verifyNoInteractions(persistenceService);
    }

    @Test
    void 탈퇴한_상대방과는_시뮬레이션을_시작하지_않는다() {
        given(userRepository.findByIdForUpdate(TARGET_ID))
            .willReturn(Optional.of(user(UserStatus.WITHDRAWN)));

        assertThatThrownBy(() -> service.admit(USER_ID, TARGET_ID))
            .isInstanceOfSatisfying(AiSimulationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(AiSimulationErrorCode.TARGET_USER_NOT_FOUND));

        verifyNoInteractions(sessionRepository, persistenceService);
    }

    @Test
    void 탈퇴한_요청자는_시뮬레이션을_시작할_수_없다() {
        given(userRepository.findByIdForUpdate(USER_ID))
            .willReturn(Optional.of(user(UserStatus.WITHDRAWN)));

        assertThatThrownBy(() -> service.admit(USER_ID, TARGET_ID))
            .isInstanceOf(AuthenticationRequiredException.class);

        verifyNoInteractions(sessionRepository, persistenceService);
    }

    private AiSimulationSessionRepository.ParticipantPair pair() {
        return new AiSimulationSessionRepository.ParticipantPair() {
            @Override
            public Long getUserId() {
                return USER_ID;
            }

            @Override
            public Long getTargetUserId() {
                return TARGET_ID;
            }
        };
    }

    private AiSimulationSession existingSession(Long id, AiSimulationSessionStatus status) {
        AiSimulationSession session = new AiSimulationSession(USER_ID, TARGET_ID, CREATED_AT);
        ReflectionTestUtils.setField(session, "id", id);
        ReflectionTestUtils.setField(session, "status", status);
        return session;
    }

    private User user(UserStatus status) {
        User user = User.create("사용자", LocalDate.of(2000, 1, 1), Gender.MALE, CREATED_AT);
        ReflectionTestUtils.setField(user, "status", status);
        return user;
    }
}
