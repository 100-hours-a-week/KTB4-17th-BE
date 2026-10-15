package com.team.dating_backend.profiletrust.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.file.entity.FileUploadIntent;
import com.team.dating_backend.file.repository.FileUploadIntentRepository;
import com.team.dating_backend.file.repository.JpaFileUploadIntentRepositoryAdapter;
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
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.FaceVerificationStatus;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
@Import({FaceVerificationSessionStore.class, JpaFileUploadIntentRepositoryAdapter.class})
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
        files.deleteIntent(photoId);
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

    @Test
    void 다섯번_RETRY의_상태와_JSON_사유를_저장하고_추가접수를_거부한다() {
        Long id = create().sessionId();
        for (int i = 1; i <= 5; i++) {
            store.claim(ownerId, id, i, "hash-" + i, "video/webm");
            store.finish(ownerId, id, i, new VerificationResponse(VerificationDecision.RETRY,
                List.of(VerificationReasonCode.LIVENESS_FAILED), new Liveness(false)));
        }
        var response = store.get(ownerId, id);
        assertThat(response.status()).isEqualTo(VerificationSessionStatus.EXHAUSTED);
        assertThat(response.attempt().reasonCodes()).containsExactly(VerificationReasonCode.LIVENESS_FAILED);
        assertThatThrownBy(() -> store.claim(ownerId, id, 6, "hash-6", "video/webm"))
            .isInstanceOfSatisfying(ProfileTrustBusinessException.class, error -> assertThat(error.getErrorCode())
                .isEqualTo(ProfileTrustErrorCode.VERIFICATION_ATTEMPT_LIMIT_REACHED));
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
