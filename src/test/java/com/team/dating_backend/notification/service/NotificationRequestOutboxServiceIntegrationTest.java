package com.team.dating_backend.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.enums.NotificationOutboxStatus;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.exception.NotificationInvalidCreateRequestException;
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
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
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
    {NotificationRequestOutboxService.class, NotificationRequestOutboxCodec.class,
        NotificationCreateRequestedValidator.class}
)
@Testcontainers
class NotificationRequestOutboxServiceIntegrationTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-10-20T01:02:03.123456789Z");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 12, 0, 0, 123456000);

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private NotificationRequestOutboxService service;
    @Autowired
    private NotificationRequestOutboxRepository repository;
    @Autowired
    private NotificationRequestOutboxCodec codec;
    @Autowired
    private UserRepository users;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private TransactionTemplate transaction;
    private Long userId;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        userId = transaction.execute(tx -> users.saveAndFlush(user()).getId());
    }

    @AfterEach
    void cleanUp() {
        transaction.executeWithoutResult(tx -> {
            jdbc.update("DELETE FROM notification_request_outbox");
            jdbc.update("DELETE FROM profiles");
            jdbc.update("DELETE FROM users");
        });
    }

    @Test
    void 커밋한_요청을_재조회해_원본_계약과_사건시각을_복원하고_UTC_기록시각을_별도로_생성한다() {
        var request = request("LIKE_SENT:501");
        var before = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        Long id = transaction.execute(tx -> service.append(request).getId());
        var after = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("INSERT INTO profiles (user_id, created_at, updated_at, nickname) VALUES (?, ?, ?, ?)",
            userId, NOW, NOW, "하늘");
        jdbc.update("UPDATE profiles SET nickname = '바다' WHERE user_id = ?", userId);

        var stored = repository.findById(id).orElseThrow();

        assertThat(service.restore(stored)).isEqualTo(request);
        assertThat(stored.getCreatedAt()).isBetween(before, after);
        assertThat(stored.getCreatedAt()).isNotEqualTo(LocalDateTime.ofInstant(REQUESTED_AT, ZoneOffset.UTC));
        assertThat(stored.getNextAttemptAt()).isEqualTo(stored.getCreatedAt());
        assertThat(stored.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(stored.getFailureCount()).isZero();
    }

    @Test
    void 트랜잭션_없는_저장은_거부한다() {
        assertThatThrownBy(() -> service.append(request("LIKE_SENT:501")))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(repository.count()).isZero();
    }

    @Test
    void 호출한_트랜잭션이_롤백되면_Outbox도_함께_롤백한다() {
        transaction.executeWithoutResult(tx -> {
            service.append(request("LIKE_SENT:501"));
            assertThat(repository.count()).isEqualTo(1);
            tx.setRollbackOnly();
        });

        assertThat(repository.count()).isZero();
    }

    @Test
    void 중복_요청의_저장_실패가_호출한_트랜잭션의_다른_변경도_롤백한다() {
        var request = request("LIKE_SENT:501");
        transaction.executeWithoutResult(tx -> service.append(request));
        long before = users.count();

        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> {
            users.saveAndFlush(user());
            service.append(request);
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(users.count()).isEqualTo(before);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void 후보는_시각과_ID_순서로_제한하고_미래_요청과_완료_격리_요청은_제외한다() {
        List<Long> ids = transaction.execute(tx -> {
            var later = save("LATER", NOW.minusSeconds(1));
            var earlier = save("EARLIER", NOW.minusSeconds(2));
            var sameTime = save("SAME_TIME", NOW.minusSeconds(2));
            var boundary = save("BOUNDARY", NOW);
            save("FUTURE", NOW.plusNanos(1000));
            var published = save("PUBLISHED", NOW.minusSeconds(10));
            var failed = save("FAILED", NOW.minusSeconds(10));
            jdbc.update("UPDATE notification_request_outbox SET status = 'PUBLISHED', published_at = ? WHERE id = ?",
                NOW, published.getId());
            jdbc.update("UPDATE notification_request_outbox SET status = 'FAILED' WHERE id = ?", failed.getId());
            return List.of(earlier.getId(), sameTime.getId(), later.getId(), boundary.getId());
        });

        assertThat(service.findDue(NOW, 2)).extracting(NotificationRequestOutbox::getId)
            .containsExactly(ids.get(0), ids.get(1));
        assertThat(service.findDue(NOW, 10)).extracting(NotificationRequestOutbox::getId)
            .containsExactlyElementsOf(ids);
        assertThat(service.findDue(NOW, 10)).extracting(NotificationRequestOutbox::getId)
            .containsExactlyElementsOf(ids);
        assertThat(service.findDue(NOW, 10))
            .allSatisfy(entry -> assertThat(entry.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING));
    }

    @Test
    void 마이크로초_미만_기준시각을_반올림해서_미래_요청을_미리_조회하지_않는다() {
        Long id = transaction.execute(tx -> save("PRECISION", NOW.plusNanos(1000)).getId());

        assertThat(service.findDue(NOW.plusNanos(999), 10)).isEmpty();
        assertThat(service.findDue(NOW.plusNanos(1000), 10)).extracting(NotificationRequestOutbox::getId)
            .containsExactly(id);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void 조회_건수가_양수가_아니면_거부한다(int limit) {
        assertThatThrownBy(() -> service.findDue(NOW, limit)).isInstanceOf(NotificationBusinessException.class);
    }

    @Test
    void 조회_기준시각이_없으면_거부한다() {
        assertThatThrownBy(() -> service.findDue(null, 10)).isInstanceOf(NotificationBusinessException.class);
    }

    @ParameterizedTest
    @MethodSource("mismatchedColumns")
    void 저장_컬럼과_payload의_식별정보가_다르면_복원을_거부한다(String column, Object value) {
        Long id = transaction.execute(tx -> service.append(request("LIKE_SENT:501")).getId());
        jdbc.update("UPDATE notification_request_outbox SET " + column + " = ? WHERE id = ?", value, id);
        var stored = repository.findById(id).orElseThrow();

        assertThatThrownBy(() -> service.restore(stored)).isInstanceOf(NotificationInvalidCreateRequestException.class);
    }

    @Test
    void JSON_문법만_유효한_행도_후보로_조회하되_요청_복원시에는_계약을_검증한다() {
        Long invalidId = transaction.execute(tx -> repository.saveAndFlush(new NotificationRequestOutbox(
            "INVALID", userId, NotificationType.LIKE, "{}", NOW)).getId());
        transaction.executeWithoutResult(tx -> save("VALID", NOW));
        var candidates = service.findDue(NOW, 10);

        assertThat(candidates).hasSize(2);
        assertThatThrownBy(() -> service.restore(candidates.getFirst()))
            .isInstanceOf(NotificationInvalidCreateRequestException.class);
        assertThat(candidates.getFirst().getId()).isEqualTo(invalidId);
        assertThat(service.restore(candidates.getLast()).sourceEventId()).isEqualTo("VALID");
    }

    @Test
    void ID로_다시_조회할_때도_대기_상태와_시도_가능_시각을_확인한다() {
        List<Long> ids = transaction.execute(tx -> {
            var due = save("DUE_BY_ID", NOW);
            var future = save("FUTURE_BY_ID", NOW.plusNanos(1000));
            var published = save("PUBLISHED_BY_ID", NOW);
            var failed = save("FAILED_BY_ID", NOW);
            jdbc.update("UPDATE notification_request_outbox SET status = 'PUBLISHED', published_at = ? WHERE id = ?",
                NOW, published.getId());
            jdbc.update("UPDATE notification_request_outbox SET status = 'FAILED' WHERE id = ?", failed.getId());
            return List.of(due.getId(), future.getId(), published.getId(), failed.getId());
        });

        assertThat(service.findDueById(ids.getFirst(), NOW.plusNanos(999)))
            .hasValueSatisfying(outbox -> assertThat(outbox.getId()).isEqualTo(ids.getFirst()));
        for (Long id : ids.subList(1, ids.size())) {
            assertThat(service.findDueById(id, NOW.plusNanos(999))).isEmpty();
        }
        assertThat(service.findDueById(Long.MAX_VALUE, NOW)).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void 개별_조회와_성공_기록은_유효하지_않은_ID를_거부한다(Long id) {
        assertThatThrownBy(() -> service.findDueById(id, NOW)).isInstanceOf(NotificationBusinessException.class);
        assertThatThrownBy(() -> service.markPublished(id, NOW)).isInstanceOf(NotificationBusinessException.class);
    }

    @Test
    void 개별_조회와_성공_기록은_시각이_없으면_거부한다() {
        assertThatThrownBy(() -> service.findDueById(1L, null)).isInstanceOf(NotificationBusinessException.class);
        assertThatThrownBy(() -> service.markPublished(1L, null)).isInstanceOf(NotificationBusinessException.class);
    }

    @Test
    void 성공_기록은_상태와_발행시각만_바꾸고_원본_요청과_실패_이력을_보존한다() {
        Long id = transaction.execute(tx -> service.append(request("SUCCESS_UPDATE")).getId());
        jdbc.update(
            "UPDATE notification_request_outbox SET failure_count = 2, last_failure_type = 'TIMEOUT' WHERE id = ?", id);
        var before = repository.findById(id).orElseThrow();

        service.markPublished(id, NOW.plusNanos(999));

        var after = repository.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(NotificationOutboxStatus.PUBLISHED);
        assertThat(after.getPublishedAt()).isEqualTo(NOW);
        assertThat(after.getSourceEventId()).isEqualTo(before.getSourceEventId());
        assertThat(after.getRecipientUserId()).isEqualTo(before.getRecipientUserId());
        assertThat(after.getType()).isEqualTo(before.getType());
        assertThat(after.getPayload()).isEqualTo(before.getPayload());
        assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt());
        assertThat(after.getNextAttemptAt()).isEqualTo(before.getNextAttemptAt());
        assertThat(after.getFailureCount()).isEqualTo(2);
        assertThat(after.getLastFailureType()).isEqualTo("TIMEOUT");
        assertThat(service.restore(after)).isEqualTo(request("SUCCESS_UPDATE"));
    }

    @ParameterizedTest
    @EnumSource(value = NotificationOutboxStatus.class, names = {"PUBLISHED", "FAILED"})
    void 성공_기록은_대기_상태가_아닌_행을_덮어쓰지_않는다(NotificationOutboxStatus status) {
        Long id = transaction.execute(tx -> service.append(request("TERMINAL_UPDATE")).getId());
        jdbc.update("UPDATE notification_request_outbox SET status = ?, published_at = ? WHERE id = ?",
            status.name(), status == NotificationOutboxStatus.PUBLISHED ? NOW : null, id);
        var before = jdbc.queryForMap("SELECT * FROM notification_request_outbox WHERE id = ?", id);

        assertThatThrownBy(() -> service.markPublished(id, NOW.plusSeconds(1)))
            .isInstanceOf(NotificationBusinessException.class);

        assertThat(jdbc.queryForMap("SELECT * FROM notification_request_outbox WHERE id = ?", id)).isEqualTo(before);
    }

    @Test
    void 성공_기록은_존재하지_않는_행을_성공으로_취급하지_않는다() {
        assertThatThrownBy(() -> service.markPublished(Long.MAX_VALUE, NOW))
            .isInstanceOf(NotificationBusinessException.class);
        assertThat(repository.count()).isZero();
    }

    @Test
    void 성공_기록은_호출한_트랜잭션과_분리된_트랜잭션에서_커밋한다() {
        Long id = transaction.execute(tx -> service.append(request("INDEPENDENT_UPDATE")).getId());

        transaction.executeWithoutResult(tx -> {
            service.markPublished(id, NOW);
            tx.setRollbackOnly();
        });

        var stored = repository.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(NotificationOutboxStatus.PUBLISHED);
        assertThat(stored.getPublishedAt()).isEqualTo(NOW);
    }

    private NotificationRequestOutbox save(String sourceEventId, LocalDateTime dueAt) {
        var request = request(sourceEventId);
        return repository.saveAndFlush(new NotificationRequestOutbox(request.sourceEventId(), request.recipientUserId(),
            request.type(), codec.encode(request), dueAt));
    }

    private NotificationCreateRequested request(String sourceEventId) {
        return new NotificationCreateRequested(1, sourceEventId, userId, NotificationType.LIKE,
            "새로운 좋아요😀", "하늘님이 마음을 전했어요.", "PROFILE", 8L, REQUESTED_AT);
    }

    private User user() {
        var user = User.create("수신자", LocalDate.of(2000, 1, 1), Gender.FEMALE, NOW);
        user.activate(NOW);
        return user;
    }

    private static Stream<Arguments> mismatchedColumns() {
        return Stream.of(Arguments.of("source_event_id", "OTHER_EVENT"),
            Arguments.of("recipient_user_id", 999999L), Arguments.of("type", "MATCH"));
    }
}
