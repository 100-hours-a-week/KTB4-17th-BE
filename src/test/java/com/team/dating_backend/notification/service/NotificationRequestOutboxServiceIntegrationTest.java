package com.team.dating_backend.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.enums.NotificationOutboxStatus;
import com.team.dating_backend.notification.enums.NotificationType;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
        var candidates = repository.findAll();

        assertThat(candidates).hasSize(2);
        assertThatThrownBy(() -> service.restore(candidates.getFirst()))
            .isInstanceOf(NotificationInvalidCreateRequestException.class);
        assertThat(candidates.getFirst().getId()).isEqualTo(invalidId);
        assertThat(service.restore(candidates.getLast()).sourceEventId()).isEqualTo("VALID");
    }

    private NotificationRequestOutbox save(String sourceEventId, LocalDateTime dueAt) {
        var request = request(sourceEventId);
        return repository.saveAndFlush(new NotificationRequestOutbox(request.sourceEventId(), request.recipientUserId(),
            request.type(), codec.encode(request), dueAt));
    }

    private NotificationCreateRequested request(String sourceEventId) {
        return new NotificationCreateRequested(sourceEventId, userId, NotificationType.LIKE,
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
