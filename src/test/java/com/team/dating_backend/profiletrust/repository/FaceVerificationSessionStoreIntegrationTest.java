package com.team.dating_backend.profiletrust.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.activityregion.entity.ActivityRegion;
import com.team.dating_backend.activityregion.repository.ActivityRegionRepository;
import com.team.dating_backend.file.entity.File;
import com.team.dating_backend.file.entity.FileUploadIntent;
import com.team.dating_backend.file.repository.FileJpaRepository;
import com.team.dating_backend.file.repository.FileUploadIntentRepository;
import com.team.dating_backend.file.repository.JpaFileRepositoryAdapter;
import com.team.dating_backend.file.repository.JpaFileUploadIntentRepositoryAdapter;
import com.team.dating_backend.onboarding.service.OnboardingCompletionService;
import com.team.dating_backend.onboarding.service.OnboardingRequirementsCalculator;
import com.team.dating_backend.profile.dto.request.ProfileImageSaveItemRequest;
import com.team.dating_backend.profile.dto.request.ProfileImageSaveRequest;
import com.team.dating_backend.profile.entity.Profile;
import com.team.dating_backend.profile.enums.BodyType;
import com.team.dating_backend.profile.enums.Drinking;
import com.team.dating_backend.profile.enums.EducationLevel;
import com.team.dating_backend.profile.enums.JobCategory;
import com.team.dating_backend.profile.enums.Mbti;
import com.team.dating_backend.profile.enums.Religion;
import com.team.dating_backend.profile.enums.Smoking;
import com.team.dating_backend.profile.repository.ProfileImageRepository;
import com.team.dating_backend.profile.repository.ProfileRepository;
import com.team.dating_backend.profile.service.ProfileImageSaveService;
import com.team.dating_backend.profiletrust.dto.ai.FaceVerificationAiPayloads.ChallengeResponse;
import com.team.dating_backend.profiletrust.dto.ai.FaceVerificationAiPayloads.Liveness;
import com.team.dating_backend.profiletrust.dto.ai.FaceVerificationAiPayloads.VerificationResponse;
import com.team.dating_backend.profiletrust.dto.response.FaceVerificationResponse;
import com.team.dating_backend.profiletrust.enums.ProfileTrustErrorCode;
import com.team.dating_backend.profiletrust.enums.VerificationDecision;
import com.team.dating_backend.profiletrust.enums.VerificationReasonCode;
import com.team.dating_backend.profiletrust.enums.VerificationSessionStatus;
import com.team.dating_backend.profiletrust.exception.ProfileTrustBusinessException;
import com.team.dating_backend.profiletrust.service.FaceVerificationSessionStore;
import com.team.dating_backend.profiletrust.service.ProfileTrustOnboardingGate;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.FaceVerificationStatus;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.enums.UserStatus;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@Import(
    {FaceVerificationSessionStore.class, JpaFileUploadIntentRepositoryAdapter.class,
        JpaFileRepositoryAdapter.class, ProfileTrustOnboardingGate.class, ProfileImageSaveService.class,
        OnboardingCompletionService.class, OnboardingRequirementsCalculator.class}
)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FaceVerificationSessionStoreIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private FaceVerificationSessionStore store;
    @Autowired
    private FaceVerificationSessionRepository sessions;
    @Autowired
    private FaceVerificationAttemptRepository attempts;
    @Autowired
    private FileUploadIntentRepository files;
    @Autowired
    private UserRepository users;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ProfileTrustOnboardingGate gate;
    @Autowired
    private ProfileImageSaveService imageService;
    @Autowired
    private FileJpaRepository fileRows;
    @Autowired
    private ProfileRepository profiles;
    @Autowired
    private ProfileImageRepository images;
    @Autowired
    private ActivityRegionRepository regions;

    private Long ownerId;
    private Long photoId;

    @BeforeEach
    void setUp() {
        ownerId = users.saveAndFlush(User.create("woo", LocalDate.of(2000, 1, 1), Gender.MALE,
            LocalDateTime.now())).getId();
        FileUploadIntent photo = FileUploadIntent.create(ownerId, "staging/photo", "files/photo",
            "photo.png", "image/png", LocalDateTime.now().plusMinutes(10));
        photo.bindValidatedVersion("etag-1");
        photoId = files.saveAndFlush(photo).getId();
    }

    @AfterEach
    void cleanUp() {
        attempts.deleteAllInBatch();
        sessions.deleteAllInBatch();
        images.deleteAllInBatch();
        profiles.deleteAllInBatch();
        files.deleteIntent(photoId);
        fileRows.deleteAllInBatch();
        regions.deleteAllInBatch();
        users.deleteById(ownerId);
    }

    @Test
    void 동시_생성은_하나의_사용자_세션만_만든다() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                start.await();
                return create().sessionId();
            });
            var second = executor.submit(() -> {
                start.await();
                return create().sessionId();
            });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(sessions.count()).isEqualTo(1);
    }

    @Test
    void 동시_제출은_한_건만_접수하고_한번만_차감한다() throws Exception {
        Long id = create().sessionId();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                start.await();
                return tryClaim(id);
            });
            var second = executor.submit(() -> {
                start.await();
                return tryClaim(id);
            });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("ACQUIRED", "VERIFICATION_IN_PROGRESS");
        }
        assertThat(attempts.count()).isEqualTo(1);
        assertThat(store.get(ownerId, id).attemptsUsed()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = VerificationDecision.class, names = {"RETRY", "NOT_VERIFIED"})
    void 다섯번_미통과의_상태와_JSON_사유를_저장하고_추가접수를_거부한다(VerificationDecision decision) {
        Long id = create().sessionId();
        VerificationReasonCode reason = decision == VerificationDecision.NOT_VERIFIED
            ? VerificationReasonCode.FACE_MISMATCH
            : VerificationReasonCode.LIVENESS_FAILED;
        for (int i = 1; i <= 5; i++) {
            store.claim(ownerId, id, i, "hash-" + i, "video/webm");
            var result = store.finish(ownerId, id, i, new VerificationResponse(decision,
                List.of(reason), new Liveness(decision == VerificationDecision.NOT_VERIFIED)));
            assertThat(result.canRetry()).isEqualTo(i < 5);
            assertThat(result.attempts()).hasSize(i);
        }
        var response = store.get(ownerId, id);
        assertThat(response.status()).isEqualTo(VerificationSessionStatus.EXHAUSTED);
        assertThat(response.attempt().reasonCodes()).containsExactly(reason);
        assertThat(response.attempts()).extracting(FaceVerificationResponse.Attempt::attemptNo)
            .containsExactly(1, 2, 3, 4, 5);
        assertThat(response.attempts()).allSatisfy(attempt -> {
            assertThat(attempt.decision()).isEqualTo(decision);
            assertThat(attempt.reasonCodes()).containsExactly(reason);
        });
        assertThatThrownBy(() -> store.claim(ownerId, id, 6, "hash-6", "video/webm"))
            .isInstanceOfSatisfying(ProfileTrustBusinessException.class, error -> assertThat(error.getErrorCode())
                .isEqualTo(ProfileTrustErrorCode.VERIFICATION_ATTEMPT_LIMIT_REACHED));
    }

    @Test
    void 불일치와_RETRY가_섞여도_마지막_성공을_저장하고_이전_사유를_유지한다() {
        Long id = create().sessionId();
        for (int i = 1; i <= 4; i++) {
            store.claim(ownerId, id, i, "hash-" + i, "video/webm");
            boolean mismatch = i % 2 == 1;
            store.finish(ownerId, id, i, new VerificationResponse(
                mismatch ? VerificationDecision.NOT_VERIFIED : VerificationDecision.RETRY,
                List.of(
                    mismatch ? VerificationReasonCode.FACE_MISMATCH : VerificationReasonCode.FACE_COMPARISON_FAILED),
                new Liveness(true)));
        }
        assertThat(create().sessionId()).isEqualTo(id);
        store.claim(ownerId, id, 5, "hash-5", "video/webm");
        var finished = store.finish(ownerId, id, 5,
            new VerificationResponse(VerificationDecision.VERIFIED, List.of(), new Liveness(true)));
        var reloaded = store.get(ownerId, id);
        assertThat(reloaded).isEqualTo(finished);
        assertThat(reloaded.status()).isEqualTo(VerificationSessionStatus.VERIFIED);
        assertThat(reloaded.canRetry()).isFalse();
        assertThat(reloaded.attempts()).extracting(FaceVerificationResponse.Attempt::decision).containsExactly(
            VerificationDecision.NOT_VERIFIED, VerificationDecision.RETRY, VerificationDecision.NOT_VERIFIED,
            VerificationDecision.RETRY, VerificationDecision.VERIFIED);
        assertThat(reloaded.attempts().get(1).reasonCodes())
            .containsExactly(VerificationReasonCode.FACE_COMPARISON_FAILED);
        assertThat(reloaded.attempt().reasonCodes()).isEmpty();
    }

    @Test
    void 만료_거절에도_만료상태를_커밋하고_늦은_성공을_무시한다() {
        Long id = create().sessionId();
        store.claim(ownerId, id, 1, "hash", "video/webm");
        jdbc.update("UPDATE face_verification_sessions SET expires_at = ? WHERE id = ?",
            LocalDateTime.now().minusSeconds(1), id);
        var response = store.finish(ownerId, id, 1,
            new VerificationResponse(VerificationDecision.VERIFIED, List.of(), new Liveness(true)));
        assertThat(response.status()).isEqualTo(VerificationSessionStatus.EXPIRED);
        assertThat(response.attempt().errorCode()).isEqualTo(ProfileTrustErrorCode.VERIFICATION_SESSION_EXPIRED);
        assertThat(sessions.findById(id).orElseThrow().getStatus()).isEqualTo(VerificationSessionStatus.EXPIRED);
        assertThat(users.findById(ownerId).orElseThrow().getFaceVerificationStatus())
            .isEqualTo(FaceVerificationStatus.NOT_VERIFIED);
    }

    @ParameterizedTest
    @EnumSource(VerificationDecision.class)
    void 인증에_성공한_파일을_정면사진으로_등록한_경우에만_활성화한다(VerificationDecision decision) {
        Profile profile = profile(true);
        Long sessionId = finishVerification(decision);
        File file = completePhoto();

        saveImage(file);

        boolean verified = decision == VerificationDecision.VERIFIED;
        User user = users.findById(ownerId).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(verified ? UserStatus.ACTIVE : UserStatus.ONBOARDING);
        assertThat(user.getFaceVerificationStatus()).isEqualTo(
            verified ? FaceVerificationStatus.VERIFIED : FaceVerificationStatus.NOT_VERIFIED);
        assertThat(gate.isSatisfied(ownerId, profile.getId())).isEqualTo(verified);
        assertThat(sessions.findById(sessionId).orElseThrow().getVerifiedFileId())
            .isEqualTo(verified ? file.getId() : null);
    }

    @Test
    void 임시요청_삭제후에도_인증을_유지하고_다른_정면사진으로_교체하면_완료를_막는다() {
        Profile profile = profile(false);
        finishVerification(VerificationDecision.VERIFIED);
        File verifiedFile = completePhoto();
        saveImage(verifiedFile);
        files.deleteIntent(photoId);

        assertThat(gate.isSatisfied(ownerId, profile.getId())).isTrue();
        assertThat(gate.isSatisfied(ownerId + 1, profile.getId())).isFalse();
        assertThat(users.findById(ownerId).orElseThrow().getStatus()).isEqualTo(UserStatus.ONBOARDING);

        File replacement = fileRows.saveAndFlush(File.create(
            ownerId, "files/replacement", "other.png", "image/png", 32L));
        saveImage(replacement);

        assertThat(gate.isSatisfied(ownerId, profile.getId())).isFalse();
        User user = users.findById(ownerId).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(UserStatus.ONBOARDING);
        assertThat(user.getFaceVerificationStatus()).isEqualTo(FaceVerificationStatus.NOT_VERIFIED);
    }

    @Test
    void 사진을_변경해_다시_검사해도_이전_영상인증으로_완료할_수_없다() {
        Profile profile = profile(true);
        Long sessionId = finishVerification(VerificationDecision.VERIFIED);
        files.claimForProcessing(photoId, ownerId, LocalDateTime.now());
        files.completeValidation(photoId, ownerId, "etag-2");

        saveImage(completePhoto());

        assertThat(gate.isSatisfied(ownerId, profile.getId())).isFalse();
        assertThat(sessions.findById(sessionId).orElseThrow().getVerifiedFileId()).isNull();
        assertThat(users.findById(ownerId).orElseThrow().getStatus()).isEqualTo(UserStatus.ONBOARDING);
    }

    private Long finishVerification(VerificationDecision decision) {
        Long id = create().sessionId();
        store.claim(ownerId, id, 1, "hash", "video/webm");
        List<VerificationReasonCode> reasons = switch (decision) {
            case VERIFIED -> List.of();
            case RETRY -> List.of(VerificationReasonCode.LIVENESS_FAILED);
            case NOT_VERIFIED -> List.of(VerificationReasonCode.FACE_MISMATCH);
        };
        store.finish(ownerId, id, 1,
            new VerificationResponse(decision, reasons, new Liveness(decision != VerificationDecision.RETRY)));
        return id;
    }

    private File completePhoto() {
        files.claimForProcessing(photoId, ownerId, LocalDateTime.now());
        return files.complete(photoId, ownerId,
            File.create(ownerId, "files/photo", "photo.png", "image/png", 32L));
    }

    private void saveImage(File file) {
        imageService.saveProfileImages(ownerId,
            new ProfileImageSaveRequest(List.of(new ProfileImageSaveItemRequest(file.getId(), true))));
    }

    private Profile profile(boolean personaComplete) {
        User user = users.findById(ownerId).orElseThrow();
        if (personaComplete) {
            user.confirmPersonaOnboarding(LocalDateTime.now());
            users.saveAndFlush(user);
        }
        ActivityRegion region = regions.saveAndFlush(new ActivityRegion(
            "test-region", "서울특별시", "강남구", new BigDecimal("37.5"), new BigDecimal("127.0")));
        Profile profile = new Profile(user);
        profile.updateProfile(region, "woo", (short) 175, BodyType.AVERAGE, EducationLevel.BACHELOR,
            JobCategory.IT_DATA, Religion.NONE, Mbti.INTJ, Drinking.SOCIAL, Smoking.NON_SMOKER);
        return profiles.saveAndFlush(profile);
    }

    private String tryClaim(Long id) {
        try {
            store.claim(ownerId, id, 1, "same-hash", "video/webm");
            return "ACQUIRED";
        } catch (ProfileTrustBusinessException exception) {
            return exception.getErrorCode().name();
        }
    }

    private FaceVerificationResponse create() {
        return store.create(ownerId, photoId, "etag-1", new ChallengeResponse(
            "signed-token", List.of("LOOK_STRAIGHT"), OffsetDateTime.now().plusMinutes(5)));
    }
}
