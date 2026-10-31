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
import java.time.temporal.ChronoUnit;
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
import org.springframework.dao.DataIntegrityViolationException;
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
    {NotificationOutboxFailureService.class, NotificationOutboxRetryPolicy.class,
        NotificationRequestOutboxService.class, NotificationRequestOutboxCodec.class,
        NotificationCreateRequestedValidator.class}
)
@Testcontainers
class NotificationOutboxFailureServiceIntegrationTest {

    private static final LocalDateTime FAILED_AT = LocalDateTime.of(2026, 10, 30, 12, 0, 0, 123456789);

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private NotificationOutboxFailureService service;
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
    void 첫_실패는_원본을_보존하며_횟수와_원인과_다음_시각만_기록한다() {
        var before = new LinkedHashMap<>(row());

        var result = service.recordFailure(id, NotificationFailureCode.PUBLISH_RESULT_TIMEOUT, FAILED_AT);

        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.exhausted()).isFalse();
        var stored = repository.findById(id).orElseThrow();
        LocalDateTime next = FAILED_AT.plusSeconds(1).truncatedTo(ChronoUnit.MICROS);
        assertThat(stored.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(stored.getFailureCount()).isEqualTo(1);
        assertThat(stored.getLastFailureType()).isEqualTo("PUBLISH_RESULT_TIMEOUT");
        assertThat(stored.getNextAttemptAt()).isEqualTo(next);
        assertThat(outboxService.restore(stored)).isEqualTo(request);
        before.remove("failure_count");
        before.remove("last_failure_type");
        before.remove("next_attempt_at");
        assertThat(row()).containsAllEntriesOf(before);
        assertThat(outboxService.findDue(next.minusNanos(1), 10)).isEmpty();
        assertThat(outboxService.findDue(next, 10)).extracting(outbox -> outbox.getId()).containsExactly(id);
    }

    @Test
    void 실패_한도를_소진한_요청만_FAILED로_전환하고_추가_조회에서_제외한다() {
        for (int count = 1; count <= 5; count++) {
            var result = service.recordFailure(id, NotificationFailureCode.PUBLISH_RESULT_FAILED, FAILED_AT);
            var stored = repository.findById(id).orElseThrow();
            assertThat(result.failureCount()).isEqualTo(count);
            assertThat(result.exhausted()).isEqualTo(count == 5);
            assertThat(stored.getStatus())
                .isEqualTo(count == 5 ? NotificationOutboxStatus.FAILED : NotificationOutboxStatus.PENDING);
            assertThat(stored.getNextAttemptAt())
                .isEqualTo((count == 5 ? FAILED_AT : FAILED_AT.plusSeconds(1L << (count - 1)))
                    .truncatedTo(ChronoUnit.MICROS));
            assertThat(outboxService.restore(stored)).isEqualTo(request);
        }
        assertThat(outboxService.findDue(FAILED_AT.plusYears(1), 10)).isEmpty();
        var before = row();

        assertThatThrownBy(() -> service.recordFailure(id, NotificationFailureCode.PUBLISH_CALL_FAILED, FAILED_AT))
            .isInstanceOf(NotificationBusinessException.class);

        assertThat(row()).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(
        value = NotificationFailureCode.class, names = {"OUTBOX_RESTORE_FAILED", "OUTBOX_PUBLISH_REQUIRES_ATTENTION"}
    )
    void 확인이_필요한_오류도_동일_한도까지_기록한_뒤_종료한다(NotificationFailureCode code) {
        properties.setMaxFailures(2);

        assertThat(service.recordFailure(id, code, FAILED_AT).exhausted()).isFalse();
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(service.recordFailure(id, code, FAILED_AT).exhausted()).isTrue();
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(NotificationOutboxStatus.FAILED);
    }

    @Test
    void 성공_전환은_누적_실패_이력과_원본을_보존한다() {
        service.recordFailure(id, NotificationFailureCode.PUBLISH_CALL_FAILED, FAILED_AT);

        outboxService.markPublished(id, FAILED_AT.plusSeconds(1));

        var stored = repository.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(NotificationOutboxStatus.PUBLISHED);
        assertThat(stored.getFailureCount()).isEqualTo(1);
        assertThat(stored.getLastFailureType()).isEqualTo("PUBLISH_CALL_FAILED");
        assertThat(outboxService.restore(stored)).isEqualTo(request);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationOutboxStatus.class, names = {"PUBLISHED", "FAILED"})
    void 대기_상태가_아닌_행은_실패_기록으로_덮어쓰지_않는다(NotificationOutboxStatus status) {
        jdbc.update("UPDATE notification_request_outbox SET status = ? WHERE id = ?", status.name(), id);
        var before = row();

        assertThatThrownBy(() -> service.recordFailure(id, NotificationFailureCode.PUBLISH_RESULT_TIMEOUT, FAILED_AT))
            .isInstanceOf(NotificationBusinessException.class);

        assertThat(row()).isEqualTo(before);
    }

    @Test
    void 이전_실패_횟수가_다르면_조건부_갱신은_아무것도_변경하지_않는다() {
        service.recordFailure(id, NotificationFailureCode.PUBLISH_RESULT_TIMEOUT, FAILED_AT);
        var before = row();

        int updated = transaction.execute(tx -> repository.recordFailure(id, 0, 1, "PUBLISH_CALL_FAILED", FAILED_AT,
            NotificationOutboxStatus.PENDING, NotificationOutboxStatus.PENDING));

        assertThat(updated).isZero();
        assertThat(row()).isEqualTo(before);
    }

    @Test
    void 실패_기록은_별도_트랜잭션에서_커밋한다() {
        transaction.executeWithoutResult(tx -> {
            service.recordFailure(id, NotificationFailureCode.PUBLISH_RESULT_TIMEOUT, FAILED_AT);
            tx.setRollbackOnly();
        });

        assertThat(repository.findById(id).orElseThrow().getFailureCount()).isEqualTo(1);
    }

    @Test
    void 실제_DB_갱신_실패시_실패_횟수까지_함께_롤백한다() {
        var before = row();
        jdbc.execute(
            "ALTER TABLE notification_request_outbox ADD CONSTRAINT test_reject_retry_count CHECK (failure_count = 0)");
        try {
            assertThatThrownBy(
                () -> service.recordFailure(id, NotificationFailureCode.PUBLISH_RESULT_TIMEOUT, FAILED_AT))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("test_reject_retry_count");
            assertThat(row()).isEqualTo(before);
        } finally {
            jdbc.execute("ALTER TABLE notification_request_outbox DROP CHECK test_reject_retry_count");
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void 유효하지_않은_ID를_거부한다(Long invalidId) {
        assertThatThrownBy(
            () -> service.recordFailure(invalidId, NotificationFailureCode.PUBLISH_CALL_FAILED, FAILED_AT))
            .isInstanceOf(NotificationBusinessException.class);
    }

    @ParameterizedTest
    @EnumSource(
        value = NotificationFailureCode.class,
        names = {"PUBLISH_INTERRUPTED", "OUTBOX_STATE_UPDATE_FAILED", "OUTBOX_RETRY_LIMIT_EXCEEDED"}
    )
    void 중단과_DB_기록_오류와_소진_보고를_발행_실패로_중복_계산하지_않는다(NotificationFailureCode code) {
        var before = row();

        assertThatThrownBy(() -> service.recordFailure(id, code, FAILED_AT))
            .isInstanceOf(NotificationBusinessException.class);

        assertThat(row()).isEqualTo(before);
    }

    @Test
    void 원인과_시각이_없거나_행이_없으면_거부한다() {
        assertThatThrownBy(() -> service.recordFailure(id, null, FAILED_AT))
            .isInstanceOf(NotificationBusinessException.class);
        assertThatThrownBy(() -> service.recordFailure(id, NotificationFailureCode.PUBLISH_CALL_FAILED, null))
            .isInstanceOf(NotificationBusinessException.class);
        assertThatThrownBy(
            () -> service.recordFailure(Long.MAX_VALUE, NotificationFailureCode.PUBLISH_CALL_FAILED, FAILED_AT))
            .isInstanceOf(NotificationBusinessException.class);
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT * FROM notification_request_outbox WHERE id = ?", id);
    }
}
