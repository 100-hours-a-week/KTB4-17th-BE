package com.team.dating_backend.profiletrust.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.file.entity.FileUploadIntent;
import com.team.dating_backend.file.storage.FileStorage;
import com.team.dating_backend.file.storage.StoredObjectInfo;
import com.team.dating_backend.file.support.FakeFileRepository;
import com.team.dating_backend.file.support.FakeFileUploadIntentRepository;
import com.team.dating_backend.onboarding.service.OnboardingCompletionService;
import com.team.dating_backend.profiletrust.client.FaceVerificationAiClient;
import com.team.dating_backend.profiletrust.dto.ai.FaceVerificationAiPayloads.ChallengeResponse;
import com.team.dating_backend.profiletrust.dto.ai.FaceVerificationAiPayloads.Liveness;
import com.team.dating_backend.profiletrust.dto.ai.FaceVerificationAiPayloads.VerificationResponse;
import com.team.dating_backend.profiletrust.dto.response.FaceVerificationResponse.Attempt;
import com.team.dating_backend.profiletrust.entity.FaceVerificationAttempt;
import com.team.dating_backend.profiletrust.entity.FaceVerificationSession;
import com.team.dating_backend.profiletrust.enums.ProfileTrustErrorCode;
import com.team.dating_backend.profiletrust.enums.VerificationDecision;
import com.team.dating_backend.profiletrust.enums.VerificationReasonCode;
import com.team.dating_backend.profiletrust.enums.VerificationSessionStatus;
import com.team.dating_backend.profiletrust.exception.ProfileTrustBusinessException;
import com.team.dating_backend.profiletrust.repository.FaceVerificationAttemptRepository;
import com.team.dating_backend.profiletrust.repository.FaceVerificationSessionRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

class FaceVerificationServiceTest {

    private static final Long USER_ID = 41L;
    private static final byte[] PHOTO = {1, 2, 3};
    private final FaceVerificationSessionRepository sessions = mock(FaceVerificationSessionRepository.class);
    private final FaceVerificationAttemptRepository attempts = mock(FaceVerificationAttemptRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final FaceVerificationAiClient ai = mock(FaceVerificationAiClient.class);
    private final FakeFileUploadIntentRepository files = new FakeFileUploadIntentRepository(new FakeFileRepository());
    private final Map<Long, FaceVerificationSession> sessionRows = new LinkedHashMap<>();
    private final Map<String, FaceVerificationAttempt> attemptRows = new LinkedHashMap<>();
    private FileUploadIntent photo;
    private FaceVerificationService service;

    @BeforeEach
    void setUp() {
        photo = files.saveAndFlush(FileUploadIntent.create(USER_ID, "staging/photo", "files/photo",
            "photo.png", "image/png", LocalDateTime.now().plusMinutes(10)));
        photo.bindValidatedVersion("etag-1");
        given(users.findByIdForUpdate(USER_ID)).willReturn(Optional.of(mock(User.class)));
        given(sessions.saveAndFlush(any())).willAnswer(invocation -> {
            FaceVerificationSession row = invocation.getArgument(0);
            ReflectionTestUtils.setField(row, "id", (long) sessionRows.size() + 1);
            sessionRows.put(row.getId(), row);
            return row;
        });
        given(sessions.findFirstByOwnerUserIdOrderByIdDesc(anyLong())).willAnswer(invocation -> sessionRows.values()
            .stream().filter(row -> row.getOwnerUserId().equals(invocation.getArgument(0)))
            .max(Comparator.comparing(FaceVerificationSession::getId)));
        given(sessions.findOwnedForUpdate(anyLong(), anyLong()))
            .willAnswer(invocation -> Optional.ofNullable(sessionRows.get(invocation.getArgument(0)))
                .filter(row -> row.getOwnerUserId().equals(invocation.getArgument(1))));
        given(attempts.saveAndFlush(any())).willAnswer(invocation -> {
            FaceVerificationAttempt row = invocation.getArgument(0);
            attemptRows.put(row.getSessionId() + ":" + row.getAttemptNo(), row);
            return row;
        });
        given(attempts.findBySessionIdAndAttemptNo(anyLong(), anyInt())).willAnswer(invocation -> Optional
            .ofNullable(attemptRows.get(invocation.getArgument(0) + ":" + invocation.getArgument(1))));
        given(attempts.findFirstBySessionIdOrderByAttemptNoDesc(anyLong())).willAnswer(invocation -> attemptRows
            .values().stream().filter(row -> row.getSessionId().equals(invocation.getArgument(0)))
            .max(Comparator.comparing(FaceVerificationAttempt::getAttemptNo)));
        given(attempts.findAllBySessionIdOrderByAttemptNoAsc(anyLong())).willAnswer(invocation -> attemptRows
            .values().stream().filter(row -> row.getSessionId().equals(invocation.getArgument(0)))
            .sorted(Comparator.comparing(FaceVerificationAttempt::getAttemptNo)).toList());
        given(attempts.existsBySessionIdAndVideoHash(anyLong(), anyString())).willAnswer(invocation -> attemptRows
            .values().stream().anyMatch(row -> row.getSessionId().equals(invocation.getArgument(0))
                && row.getVideoHash().equals(invocation.getArgument(1))));
        given(storage.inspectUploadedObject(photo.getStagingKey())).willReturn(Optional.of(
            new StoredObjectInfo(PHOTO.length, "image/png", "etag-1", PHOTO)));
        given(storage.readUploadedObject(photo.getStagingKey(), "etag-1")).willReturn(PHOTO);
        given(ai.createChallenge()).willReturn(new ChallengeResponse(
            "signed-token", List.of("LOOK_STRAIGHT"), OffsetDateTime.now().plusMinutes(5)));
        FaceVerificationSessionStore store = new FaceVerificationSessionStore(
            sessions, attempts, files, users, mock(OnboardingCompletionService.class));
        service = new FaceVerificationService(store, ai, new VerificationVideoReader(), files, storage);
    }

    @Test
    void 통과하지_않은_사진으로_챌린지를_발급하지_않는다() {
        ReflectionTestUtils.setField(photo, "validatedVersionToken", null);
        assertError(() -> service.create(USER_ID, photo.getId()), ProfileTrustErrorCode.PRIMARY_PHOTO_NOT_READY);
        verify(ai, never()).createChallenge();
    }

    @Test
    void 같은_사진의_진행중_세션은_재사용한다() {
        Long id = createSession();
        assertThat(createSession()).isEqualTo(id);
        assertThat(sessionRows).hasSize(1);
    }

    @Test
    void VERIFIED에서_조기_종료하고_중복_재전송은_AI를_다시_호출하지_않는다() {
        Long id = createSession();
        stubResult(VerificationDecision.VERIFIED);
        var result = service.submit(USER_ID, id, 1, video("one"));
        assertThat(result.status()).isEqualTo(VerificationSessionStatus.VERIFIED);
        assertThat(result.attemptsUsed()).isEqualTo(1);
        assertThat(result.canRetry()).isFalse();
        assertThat(service.submit(USER_ID, id, 1, video("one")).attempt()).isEqualTo(result.attempt());
        assertError(() -> service.submit(USER_ID, id, 2, video("two")),
            ProfileTrustErrorCode.VERIFICATION_SESSION_CLOSED);
        verify(ai, times(1)).verify(anyString(), any(), anyString(), any(), anyString());
        verify(storage, never()).createUploadUrl(anyString(), anyString(), any());
        verify(storage, never()).promote(anyString(), anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @EnumSource(value = VerificationDecision.class, names = {"RETRY", "NOT_VERIFIED"})
    void 미통과_5회면_EXHAUSTED로_종료하고_6번째는_거부한다(VerificationDecision decision) {
        Long id = createSession();
        stubResult(decision);
        for (int i = 1; i <= 5; i++) {
            var result = service.submit(USER_ID, id, i, video("video-" + i));
            assertThat(result.attemptsUsed()).isEqualTo(i);
            assertThat(result.canRetry()).isEqualTo(i < 5);
            assertThat(result.status()).isEqualTo(i < 5
                ? VerificationSessionStatus.READY
                : VerificationSessionStatus.EXHAUSTED);
            assertThat(result.remainingAttempts()).isEqualTo(5 - i);
            assertThat(result.nextAttemptNo()).isEqualTo(i < 5 ? Integer.valueOf(i + 1) : null);
            assertThat(result.attempt().decision()).isEqualTo(decision);
            assertThat(result.attempts()).hasSize(i);
            assertThat(result.attempts()).allSatisfy(attempt -> {
                assertThat(attempt.decision()).isEqualTo(decision);
                assertThat(attempt.reasonCodes()).containsExactlyElementsOf(result(decision).reasonCodes());
            });
        }
        var finalResult = service.get(USER_ID, id);
        assertThat(finalResult.status()).isEqualTo(VerificationSessionStatus.EXHAUSTED);
        assertThat(finalResult.attempts()).extracting(Attempt::attemptNo).containsExactly(1, 2, 3, 4, 5);
        assertThat(service.submit(USER_ID, id, 5, video("video-5"))).isEqualTo(finalResult);
        assertError(() -> service.submit(USER_ID, id, 6, video("six")),
            ProfileTrustErrorCode.VERIFICATION_ATTEMPT_LIMIT_REACHED);
        verify(ai, times(5)).verify(anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    void 첫_NOT_VERIFIED_후_같은_사진의_세션과_시도이력을_재사용한다() {
        Long id = createSession();
        stubResult(VerificationDecision.NOT_VERIFIED);
        var first = service.submit(USER_ID, id, 1, video("one"));
        var resumed = service.create(USER_ID, photo.getId());
        assertThat(resumed).isEqualTo(first);
        assertThat(resumed.sessionId()).isEqualTo(id);
        assertThat(resumed.canRetry()).isTrue();
        assertThat(resumed.nextAttemptNo()).isEqualTo(2);
        assertThat(sessionRows).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 5})
    void 이전_불일치와_RETRY가_있어도_VERIFIED면_성공한다(int successfulAttempt) {
        Long id = createSession();
        for (int i = 1; i < successfulAttempt; i++) {
            stubResult(i % 2 == 1 ? VerificationDecision.NOT_VERIFIED : VerificationDecision.RETRY);
            assertThat(service.submit(USER_ID, id, i, video("video-" + i)).canRetry()).isTrue();
        }
        stubResult(VerificationDecision.VERIFIED);
        var verified = service.submit(USER_ID, id, successfulAttempt, video("verified"));
        assertThat(verified.status()).isEqualTo(VerificationSessionStatus.VERIFIED);
        assertThat(verified.canRetry()).isFalse();
        assertThat(verified.nextAttemptNo()).isNull();
        assertThat(verified.attempts()).hasSize(successfulAttempt);
        assertThat(verified.attempts().getLast().decision()).isEqualTo(VerificationDecision.VERIFIED);
        assertThat(verified.attempts().getFirst().reasonCodes()).containsExactly(VerificationReasonCode.FACE_MISMATCH);

        var replayed = service.submit(USER_ID, id, 1, video("video-1"));
        assertThat(replayed.status()).isEqualTo(VerificationSessionStatus.VERIFIED);
        assertThat(replayed.attempt().attemptNo()).isEqualTo(1);
        assertThat(replayed.attempt().decision()).isEqualTo(VerificationDecision.NOT_VERIFIED);
        assertThat(replayed.attempts()).isEqualTo(verified.attempts());
        assertError(() -> service.submit(USER_ID, id, successfulAttempt + 1, video("extra")),
            ProfileTrustErrorCode.VERIFICATION_SESSION_CLOSED);
        verify(ai, times(successfulAttempt)).verify(anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    void 서로_다른_실패_사유를_누적하고_5번째_실패에서_이력을_반환한다() {
        Long id = createSession();
        var decisions = List.of(VerificationDecision.NOT_VERIFIED, VerificationDecision.RETRY,
            VerificationDecision.NOT_VERIFIED, VerificationDecision.RETRY, VerificationDecision.RETRY);
        for (int i = 0; i < decisions.size(); i++) {
            stubResult(decisions.get(i));
            service.submit(USER_ID, id, i + 1, video("video-" + i));
        }
        var exhausted = service.get(USER_ID, id);
        assertThat(exhausted.status()).isEqualTo(VerificationSessionStatus.EXHAUSTED);
        assertThat(exhausted.attempts()).extracting(Attempt::decision).containsExactlyElementsOf(decisions);
        assertThat(exhausted.attempts()).extracting(Attempt::reasonCodes).containsExactly(
            List.of(VerificationReasonCode.FACE_MISMATCH), List.of(VerificationReasonCode.LIVENESS_FAILED),
            List.of(VerificationReasonCode.FACE_MISMATCH), List.of(VerificationReasonCode.LIVENESS_FAILED),
            List.of(VerificationReasonCode.LIVENESS_FAILED));
        assertThat(exhausted.attempt()).isEqualTo(exhausted.attempts().getLast());
    }

    @Test
    void 번호_건너뛰기와_다른_본문의_재전송과_영상_재사용은_횟수를_차감하지_않는다() {
        Long id = createSession();
        stubResult(VerificationDecision.RETRY);
        assertError(() -> service.submit(USER_ID, id, 2, video("two")),
            ProfileTrustErrorCode.VERIFICATION_ATTEMPT_OUT_OF_ORDER);
        service.submit(USER_ID, id, 1, video("one"));
        assertError(() -> service.submit(USER_ID, id, 1, video("different")),
            ProfileTrustErrorCode.VERIFICATION_ATTEMPT_CONFLICT);
        assertError(() -> service.submit(USER_ID, id, 2, video("one")),
            ProfileTrustErrorCode.VERIFICATION_DUPLICATE_VIDEO);
        assertThat(service.get(USER_ID, id).attemptsUsed()).isEqualTo(1);
    }

    @Test
    void AI_오류도_한번_차감하고_같은_시도의_오류를_재반환한다() {
        Long id = createSession();
        given(ai.verify(anyString(), any(), anyString(), any(), anyString()))
            .willThrow(new ProfileTrustBusinessException(ProfileTrustErrorCode.AI_SERVER_UNAVAILABLE));
        assertError(() -> service.submit(USER_ID, id, 1, video("one")),
            ProfileTrustErrorCode.AI_SERVER_UNAVAILABLE);
        assertError(() -> service.submit(USER_ID, id, 1, video("one")),
            ProfileTrustErrorCode.AI_SERVER_UNAVAILABLE);
        var result = service.get(USER_ID, id);
        assertThat(result.attemptsUsed()).isEqualTo(1);
        assertThat(result.nextAttemptNo()).isEqualTo(2);
        assertThat(result.attempt().errorCode()).isEqualTo(ProfileTrustErrorCode.AI_SERVER_UNAVAILABLE);
        assertThat(result.attempts()).containsExactly(result.attempt());
        verify(ai, times(1)).verify(anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    void 대표사진이_변경되면_세션을_종료하고_AI를_호출하지_않는다() {
        Long id = createSession();
        given(storage.inspectUploadedObject(photo.getStagingKey())).willReturn(Optional.of(
            new StoredObjectInfo(PHOTO.length, "image/png", "etag-2", PHOTO)));
        assertError(() -> service.submit(USER_ID, id, 1, video("one")),
            ProfileTrustErrorCode.PRIMARY_PHOTO_CHANGED);
        assertThat(service.get(USER_ID, id).status()).isEqualTo(VerificationSessionStatus.EXPIRED);
        verify(ai, never()).verify(anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    void 만료후_도착한_VERIFIED는_반영하지_않는다() {
        Long id = createSession();
        given(ai.verify(anyString(), any(), anyString(), any(), anyString())).willAnswer(invocation -> {
            ReflectionTestUtils.setField(sessionRows.get(id), "expiresAt", LocalDateTime.now().minusSeconds(1));
            return result(VerificationDecision.VERIFIED);
        });
        assertError(() -> service.submit(USER_ID, id, 1, video("one")),
            ProfileTrustErrorCode.VERIFICATION_SESSION_EXPIRED);
        assertThat(service.get(USER_ID, id).status()).isEqualTo(VerificationSessionStatus.EXPIRED);
    }

    @Test
    void 만료된_세션의_신규_시도는_차감하지_않는다() {
        Long id = createSession();
        ReflectionTestUtils.setField(sessionRows.get(id), "expiresAt", LocalDateTime.now().minusSeconds(1));
        assertError(() -> service.submit(USER_ID, id, 1, video("one")),
            ProfileTrustErrorCode.VERIFICATION_SESSION_EXPIRED);
        assertThat(service.get(USER_ID, id).attemptsUsed()).isZero();
    }

    @Test
    void 라이브니스_실패를_VERIFIED로_반환한_비정상_응답은_거부한다() {
        Long id = createSession();
        given(ai.verify(anyString(), any(), anyString(), any(), anyString())).willReturn(
            new VerificationResponse(VerificationDecision.VERIFIED, List.of(), new Liveness(false)));
        assertError(() -> service.submit(USER_ID, id, 1, video("one")),
            ProfileTrustErrorCode.AI_SERVER_RESPONSE_INVALID);
        assertThat(service.get(USER_ID, id).canRetry()).isTrue();
    }

    @Test
    void 다른_사용자는_세션을_조회하거나_제출할_수_없다() {
        Long id = createSession();
        assertError(() -> service.get(99L, id), ProfileTrustErrorCode.VERIFICATION_SESSION_NOT_FOUND);
        assertError(() -> service.submit(99L, id, 1, video("one")),
            ProfileTrustErrorCode.VERIFICATION_SESSION_NOT_FOUND);
    }

    private Long createSession() {
        return service.create(USER_ID, photo.getId()).sessionId();
    }

    private void stubResult(VerificationDecision decision) {
        given(ai.verify(anyString(), any(), anyString(), any(), anyString())).willReturn(result(decision));
    }

    private VerificationResponse result(VerificationDecision decision) {
        List<VerificationReasonCode> reasons = switch (decision) {
            case VERIFIED -> List.of();
            case RETRY -> List.of(VerificationReasonCode.LIVENESS_FAILED);
            case NOT_VERIFIED -> List.of(VerificationReasonCode.FACE_MISMATCH);
        };
        return new VerificationResponse(decision, reasons, new Liveness(decision != VerificationDecision.RETRY));
    }

    private MockMultipartFile video(String body) {
        return new MockMultipartFile("video", "clip.webm", "video/webm", body.getBytes(StandardCharsets.UTF_8));
    }

    private void assertError(Runnable action, ProfileTrustErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ProfileTrustBusinessException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(code);
            assertThat(exception.getMessage()).isNull();
        });
    }
}
