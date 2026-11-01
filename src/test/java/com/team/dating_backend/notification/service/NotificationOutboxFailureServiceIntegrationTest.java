package com.team.dating_backend.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.notification.config.NotificationOutboxRetryProperties;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.enums.NotificationOutboxStatus;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.outbox.NotificationOutboxRetryPolicy;
import com.team.dating_backend.notification.outbox.NotificationRequestOutboxCodec;
import com.team.dating_backend.notification.repository.NotificationRequestOutboxRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    {NotificationOutboxClaimService.class, NotificationOutboxFailureService.class, NotificationOutboxRetryPolicy.class,
        NotificationRequestOutboxService.class, NotificationRequestOutboxCodec.class,
        NotificationCreateRequestedValidator.class}
)
@Testcontainers
class NotificationOutboxFailureServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private NotificationOutboxFailureService service;
    @Autowired
    private NotificationOutboxClaimService claims;
    @Autowired
    private NotificationRequestOutboxService outboxService;
    @Autowired
    private NotificationRequestOutboxRepository repository;
    @Autowired
    private NotificationOutboxRetryProperties properties;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private UserRepository users;
    @Autowired
    private JdbcTemplate jdbc;

    private TransactionTemplate transaction;
    private Long id;
    private NotificationCreateRequested request;

    @BeforeEach
    void setUp() {
        properties.setMaxFailures(5);
        properties.setInitialDelayMs(1000);
        properties.setMaxDelayMs(60_000);
        transaction = new TransactionTemplate(transactionManager);
        Long userId = transaction.execute(tx -> users.saveAndFlush(
            User.create("회원", LocalDate.of(2000, 1, 1), Gender.FEMALE, LocalDateTime.now(ZoneOffset.UTC))).getId());
        request = new NotificationCreateRequested(1, "LIKE_SENT:501", userId, NotificationType.LIKE,
            "원본 제목", "하늘님이 당신에게 마음을 전했어요.", "PROFILE", 202L,
            Instant.parse("2026-10-20T01:02:03.123456789Z"));
        id = transaction.execute(tx -> outboxService.append(request).getId());
    }

    @AfterEach
    void cleanUp() {
        transaction.executeWithoutResult(tx -> {
            jdbc.update("DELETE FROM notification_request_outbox");
            jdbc.update("DELETE FROM users");
        });
    }

    @Test
    void 첫_실패는_원본을_보존하며_DB_시각으로_재시도를_기록한다() {
        String token = claim();
        var before = new LinkedHashMap<>(row());
        var started = dbNow();

        var result = service.recordFailure(id, token, NotificationFailureCode.PUBLISH_RESULT_TIMEOUT).orElseThrow();

        var stored = repository.findById(id).orElseThrow();
        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.exhausted()).isFalse();
        assertThat(stored.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(stored.getNextAttemptAt()).isBetween(started.plusSeconds(1), dbNow().plusSeconds(1));
        assertThat(stored.getLastFailureType()).isEqualTo("PUBLISH_RESULT_TIMEOUT");
        assertThat(outboxService.restore(stored)).isEqualTo(request);
        for (String field : java.util.List.of("failure_count", "last_failure_type", "next_attempt_at",
            "processing_token", "processing_until")) {
            before.remove(field);
        }
        assertThat(row()).containsAllEntriesOf(before);
        assertThat(outboxService.findAvailable(10)).isEmpty();
    }

    @Test
    void 실패_한도를_소진한_요청만_FAILED로_전환하고_추가_기록을_거절한다() {
        String lastToken = null;
        for (int count = 1; count <= 5; count++) {
            lastToken = claim();
            var started = dbNow();
            var result = service.recordFailure(id, lastToken, NotificationFailureCode.PUBLISH_RESULT_FAILED)
                .orElseThrow();
            var stored = repository.findById(id).orElseThrow();
            assertThat(result.failureCount()).isEqualTo(count);
            assertThat(result.exhausted()).isEqualTo(count == 5);
            assertThat(stored.getStatus())
                .isEqualTo(count == 5 ? NotificationOutboxStatus.FAILED : NotificationOutboxStatus.PENDING);
            long seconds = count == 5 ? 0 : 1L << (count - 1);
            assertThat(stored.getNextAttemptAt()).isBetween(started.plusSeconds(seconds), dbNow().plusSeconds(seconds));
            assertThat(outboxService.restore(stored)).isEqualTo(request);
        }
        var before = row();
        assertThat(outboxService.findAvailable(10)).isEmpty();
        assertThat(service.recordFailure(id, lastToken, NotificationFailureCode.PUBLISH_CALL_FAILED)).isEmpty();
        assertThat(row()).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(
        value = NotificationFailureCode.class, names = {"OUTBOX_RESTORE_FAILED", "OUTBOX_PUBLISH_REQUIRES_ATTENTION"}
    )
    void 확인이_필요한_오류도_동일_한도까지_기록한다(NotificationFailureCode code) {
        properties.setMaxFailures(2);
        assertThat(service.recordFailure(id, claim(), code).orElseThrow().exhausted()).isFalse();
        assertThat(service.recordFailure(id, claim(), code).orElseThrow().exhausted()).isTrue();
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(NotificationOutboxStatus.FAILED);
    }

    @Test
    void 성공_전환은_누적_실패_이력과_원본을_보존한다() {
        service.recordFailure(id, claim(), NotificationFailureCode.PUBLISH_CALL_FAILED).orElseThrow();
        assertThat(claims.markPublished(id, claim())).isTrue();
        var stored = repository.findById(id).orElseThrow();
        assertThat(stored.getFailureCount()).isEqualTo(1);
        assertThat(stored.getLastFailureType()).isEqualTo("PUBLISH_CALL_FAILED");
        assertThat(outboxService.restore(stored)).isEqualTo(request);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationOutboxStatus.class, names = {"PUBLISHED", "FAILED"})
    void 종료된_행은_실패_기록으로_덮어쓰지_않는다(NotificationOutboxStatus status) {
        jdbc.update("UPDATE notification_request_outbox SET status = ? WHERE id = ?", status.name(), id);
        var before = row();
        assertThat(
            service.recordFailure(id, UUID.randomUUID().toString(), NotificationFailureCode.PUBLISH_RESULT_TIMEOUT))
            .isEmpty();
        assertThat(row()).isEqualTo(before);
    }

    @Test
    void 실패_기록은_별도_트랜잭션에서_커밋한다() {
        String token = claim();
        transaction.executeWithoutResult(tx -> {
            assertThat(service.recordFailure(id, token, NotificationFailureCode.PUBLISH_RESULT_TIMEOUT)).isPresent();
            tx.setRollbackOnly();
        });
        assertThat(repository.findById(id).orElseThrow().getFailureCount()).isEqualTo(1);
    }

    @Test
    void 실제_DB_오류에서는_담당과_실패_횟수를_함께_보존한다() {
        String token = claim();
        var before = row();
        jdbc.execute(
            "ALTER TABLE notification_request_outbox ADD CONSTRAINT test_reject_retry_count CHECK (failure_count = 0)");
        try {
            assertThatThrownBy(() -> service.recordFailure(id, token, NotificationFailureCode.PUBLISH_RESULT_TIMEOUT))
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("test_reject_retry_count");
            assertThat(row()).isEqualTo(before);
        } finally {
            jdbc.execute("ALTER TABLE notification_request_outbox DROP CHECK test_reject_retry_count");
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void 잘못된_ID를_거부한다(Long invalidId) {
        assertThatThrownBy(() -> service.recordFailure(invalidId, UUID.randomUUID().toString(),
            NotificationFailureCode.PUBLISH_CALL_FAILED))
            .isInstanceOf(NotificationBusinessException.class);
    }

    @ParameterizedTest
    @EnumSource(
        value = NotificationFailureCode.class, names = {"PUBLISH_INTERRUPTED", "OUTBOX_STATE_UPDATE_FAILED",
            "OUTBOX_RETRY_LIMIT_EXCEEDED", "OUTBOX_CLAIM_LOST", "OUTBOX_RELEASE_FAILED"}
    )
    void 중단과_담당_상실과_DB_오류는_발행_실패로_계산하지_않는다(NotificationFailureCode code) {
        String token = claim();
        var before = row();
        assertThatThrownBy(() -> service.recordFailure(id, token, code))
            .isInstanceOf(NotificationBusinessException.class);
        assertThat(row()).isEqualTo(before);
    }

    @Test
    void 원인이나_토큰이_없으면_거부하고_없는_행은_빈_결과를_반환한다() {
        assertThatThrownBy(() -> service.recordFailure(id, UUID.randomUUID().toString(), null))
            .isInstanceOf(NotificationBusinessException.class);
        assertThatThrownBy(() -> service.recordFailure(id, null, NotificationFailureCode.PUBLISH_CALL_FAILED))
            .isInstanceOf(NotificationBusinessException.class);
        assertThat(service.recordFailure(Long.MAX_VALUE, UUID.randomUUID().toString(),
            NotificationFailureCode.PUBLISH_CALL_FAILED)).isEmpty();
    }

    private String claim() {
        jdbc.update(
            "UPDATE notification_request_outbox SET next_attempt_at = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) WHERE id = ?",
            id);
        return claims.tryClaim(id).orElseThrow().getProcessingToken();
    }

    private LocalDateTime dbNow() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class);
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT * FROM notification_request_outbox WHERE id = ?", id);
    }
}
