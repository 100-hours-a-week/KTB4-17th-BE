package com.team.dating_backend.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.dto.result.NotificationCreationResult;
import com.team.dating_backend.notification.entity.Notification;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.repository.NotificationRepository;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.repository.UserRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({NotificationCreationService.class, NotificationPersistenceService.class, NotificationLookupService.class})
@Testcontainers
class NotificationCreationServiceIntegrationTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-10-21T01:02:03.123456789Z");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 21, 1, 2, 3, 123456000);

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private NotificationCreationService service;

    @Autowired
    private NotificationPersistenceService persistenceService;

    @Autowired
    private NotificationRepository repository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;
    private Long userId;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        userId = transaction.execute(status -> {
            User user = User.create("테스트", LocalDate.of(2000, 1, 1), Gender.FEMALE, CREATED_AT);
            user.activate(CREATED_AT);
            return userRepository.saveAndFlush(user).getId();
        });
    }

    @AfterEach
    void cleanUp() {
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM notifications WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        });
    }

    @Test
    void 저장_커밋_후_결과를_반환하고_요청_시각을_UTC와_microsecond로_보존한다() {
        NotificationCreationResult result = service.createOrLoad(request());

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(result.sourceEventId()).isEqualTo("event-1");
        assertThat(result.recipientUserId()).isEqualTo(userId);
        assertThat(result.notification().notificationId()).isPositive();
        assertThat(result.notification().type()).isEqualTo("LIKE");
        assertThat(result.notification().title()).isEqualTo("원본 제목");
        assertThat(result.notification().body()).isEqualTo("원본 본문");
        assertThat(result.notification().targetType()).isEqualTo("USER");
        assertThat(result.notification().targetId()).isEqualTo(99L);
        assertThat(result.notification().createdAt()).isEqualTo(CREATED_AT);
        assertThat(result.notification().readAt()).isNull();
        assertThat(result.deletedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT created_at FROM notifications WHERE id = ?",
            LocalDateTime.class, result.notification().notificationId())).isEqualTo(CREATED_AT);
        assertThat(repository.countVisibleUnreadByUserId(userId)).isEqualTo(1L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 중복_저장_롤백_후_기존_ID와_내용과_읽음_삭제_상태를_반환한다(boolean deleted) {
        Long notificationId = service.createOrLoad(request()).notification().notificationId();
        transaction.executeWithoutResult(status -> {
            Notification notification = repository.findById(notificationId).orElseThrow();
            notification.markAsRead(CREATED_AT.plusMinutes(1));
            if (deleted) {
                notification.markAsDeleted(CREATED_AT.plusMinutes(2));
            }
        });
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM notifications WHERE id = ?", notificationId);
        NotificationCreateRequested replay = new NotificationCreateRequested(
            1, "event-1", userId, NotificationType.LIKE, "변경된 제목", "변경된 본문", null, null,
            REQUESTED_AT.plusSeconds(3600));

        NotificationCreationResult result = service.createOrLoad(replay);

        assertThat(result.notification().notificationId()).isEqualTo(notificationId);
        assertThat(result.notification().title()).isEqualTo("원본 제목");
        assertThat(result.notification().body()).isEqualTo("원본 본문");
        assertThat(result.notification().targetType()).isEqualTo("USER");
        assertThat(result.notification().targetId()).isEqualTo(99L);
        assertThat(result.notification().createdAt()).isEqualTo(CREATED_AT);
        assertThat(result.notification().readAt()).isEqualTo(CREATED_AT.plusMinutes(1));
        assertThat(result.deletedAt()).isEqualTo(deleted ? CREATED_AT.plusMinutes(2) : null);
        assertThat(jdbc.queryForMap("SELECT * FROM notifications WHERE id = ?", notificationId)).isEqualTo(before);
        assertThat(rowCount()).isEqualTo(1L);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void 같은_요청의_동시_처리도_모두_같은_ID를_반환하고_한_행만_저장한다() throws Exception {
        int workers = 4;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<NotificationCreationResult>> futures = new ArrayList<>();
        List<NotificationCreationResult> results = new ArrayList<>();
        var executor = Executors.newFixedThreadPool(workers);
        try {
            for (int i = 0; i < workers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("동시 처리 시작 대기 초과");
                    }
                    return service.createOrLoad(request());
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<NotificationCreationResult> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(results).hasSize(workers).allSatisfy(result -> assertThat(result).isEqualTo(results.getFirst()));
        assertThat(rowCount()).isEqualTo(1L);
    }

    @Test
    void 외래_키_오류는_중복으로_처리하지_않고_다음_정상_요청은_저장한다() {
        NotificationCreateRequested missingRecipient = new NotificationCreateRequested(
            1, "event-1", Long.MAX_VALUE, NotificationType.LIKE, "제목", "본문", null, null, REQUESTED_AT);

        assertThatThrownBy(() -> service.createOrLoad(missingRecipient))
            .isInstanceOfSatisfying(DataIntegrityViolationException.class,
                exception -> assertThat(exception.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class,
                    sql -> assertThat(sql.getErrorCode()).isEqualTo(1452)));

        assertThat(rowCount()).isZero();
        assertThat(service.createOrLoad(request()).notification().notificationId()).isPositive();
        assertThat(rowCount()).isEqualTo(1L);
    }

    @Test
    void 활성_트랜잭션에서_조정_서비스를_호출하면_저장_전에_거부한다() {
        transaction.executeWithoutResult(status -> assertThatThrownBy(() -> service.createOrLoad(request()))
            .isInstanceOf(IllegalTransactionStateException.class));

        assertThat(rowCount()).isZero();
    }

    @Test
    void 저장_서비스의_독립_커밋은_호출한_트랜잭션의_롤백에도_유지된다() {
        Long notificationId = transaction.execute(status -> {
            NotificationCreationResult result = persistenceService.create(request());
            status.setRollbackOnly();
            return result.notification().notificationId();
        });

        assertThat(repository.findById(notificationId)).isPresent();
        assertThat(rowCount()).isEqualTo(1L);
    }

    @Test
    void 지원하지_않는_스키마는_errorCode로_거부하고_저장하지_않는다() {
        NotificationCreateRequested unsupported = new NotificationCreateRequested(
            2, "event-1", userId, NotificationType.LIKE, "제목", "본문", null, null, REQUESTED_AT);

        assertThatThrownBy(() -> service.createOrLoad(unsupported))
            .isInstanceOfSatisfying(NotificationBusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR));

        assertThat(rowCount()).isZero();
    }

    private NotificationCreateRequested request() {
        return new NotificationCreateRequested(1, "event-1", userId, NotificationType.LIKE,
            "원본 제목", "원본 본문", "USER", 99L, REQUESTED_AT);
    }

    private long rowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ?", Long.class, userId);
    }
}
