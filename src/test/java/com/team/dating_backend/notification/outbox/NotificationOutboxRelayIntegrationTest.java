package com.team.dating_backend.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.notification.alert.NotificationFailureReporter;
import com.team.dating_backend.notification.config.NotificationOutboxRetryProperties;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.service.NotificationOutboxFailureService;
import com.team.dating_backend.notification.service.NotificationOutboxClaimService;
import com.team.dating_backend.common.kafka.config.KafkaProducerConfiguration;
import com.team.dating_backend.notification.config.NotificationKafkaProperties;
import com.team.dating_backend.notification.config.NotificationPublishProperties;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationOutboxStatus;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationInvalidCreateRequestException;
import com.team.dating_backend.notification.kafka.NotificationRequestProducer;
import com.team.dating_backend.notification.repository.NotificationRequestOutboxRepository;
import com.team.dating_backend.notification.service.NotificationCreateRequestedValidator;
import com.team.dating_backend.notification.service.NotificationRequestOutboxService;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.team.dating_backend.notification.config.NotificationOutboxRelayProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ImportAutoConfiguration({KafkaAutoConfiguration.class, JacksonAutoConfiguration.class})
@Import(
    {NotificationOutboxRelay.class, NotificationRequestOutboxService.class, NotificationRequestOutboxCodec.class,
        NotificationCreateRequestedValidator.class, NotificationRequestProducer.class,
        NotificationOutboxClaimService.class, NotificationOutboxFailureService.class,
        NotificationOutboxRetryPolicy.class,
        NotificationOutboxFailureClassifier.class,
        NotificationOutboxRelayIntegrationTest.KafkaConfiguration.class}
)
@Testcontainers
class NotificationOutboxRelayIntegrationTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-10-20T01:02:03.123456789Z");

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @Autowired
    private NotificationOutboxRelay relay;
    @Autowired
    private NotificationOutboxClaimService claims;
    @Autowired
    private NotificationOutboxFailureService failures;
    @Autowired
    private NotificationRequestOutboxService outboxService;
    @Autowired
    private NotificationRequestOutboxRepository outboxes;
    @Autowired
    private UserRepository users;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private NotificationKafkaProperties kafkaProperties;
    @Autowired
    private NotificationPublishProperties publishProperties;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private NotificationOutboxRetryProperties retryProperties;
    @MockitoBean
    private NotificationFailureReporter failureReporter;
    @MockitoSpyBean
    private NotificationRequestProducer producer;
    @MockitoSpyBean(name = "kafkaTemplate")
    private KafkaTemplate<?, ?> kafkaTemplate;

    private TransactionTemplate transaction;
    private Long userId;
    private String topic;

    @BeforeEach
    void setUp() throws Exception {
        retryProperties.setMaxFailures(5);
        retryProperties.setInitialDelayMs(1000);
        retryProperties.setMaxDelayMs(60_000);
        transaction = new TransactionTemplate(transactionManager);
        userId = transaction.execute(tx -> users.saveAndFlush(
            User.create("회원", LocalDate.of(2000, 1, 1), Gender.FEMALE, LocalDateTime.now(ZoneOffset.UTC))).getId());
        topic = "notification-outbox-relay-" + UUID.randomUUID();
        kafkaProperties.setCreateRequestedTopic(topic);
        publishProperties.setResultTimeoutMs(15_000L);
        try (
            var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 2, (short) 1))).all().get(20, TimeUnit.SECONDS);
        }
    }

    @AfterEach
    void cleanUp() {
        Thread.interrupted();
        transaction.executeWithoutResult(tx -> {
            jdbc.update("DELETE FROM notification_request_outbox");
            jdbc.update("DELETE FROM users");
        });
    }

    @Test
    void 실제_Kafka_성공을_DB_트랜잭션_밖에서_확인한_뒤_상태와_시각만_기록한다() throws Exception {
        var request = request();
        Long id = save(request);
        jdbc.update(
            "UPDATE notification_request_outbox SET failure_count = 2, last_failure_type = 'TIMEOUT' WHERE id = ?", id);
        var before = row(id);
        assertThat(outboxService.findDue(LocalDateTime.now(ZoneOffset.UTC), 10))
            .extracting(outbox -> outbox.getId()).contains(id);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(row(id).get("processing_token")).isNotNull();
            assertThat(outboxes.findById(id).orElseThrow().getFailureCount()).isEqualTo(2);
            Object result = invocation.callRealMethod();
            assertThat(row(id).get("processing_token")).isNotNull();
            return result;
        }).when(producer).publishAndAwait(request);
        LocalDateTime startedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);

        assertThat(relay.publish(id)).isTrue();

        var published = outboxes.findById(id).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(NotificationOutboxStatus.PUBLISHED);
        assertThat(published.getPublishedAt()).isBetween(startedAt, LocalDateTime.now(ZoneOffset.UTC));
        assertThat(outboxService.restore(published)).isEqualTo(request);
        var unchanged = new LinkedHashMap<>(before);
        unchanged.remove("status");
        unchanged.remove("published_at");
        assertThat(row(id)).containsAllEntriesOf(unchanged);
        assertThat(outboxService.findDue(LocalDateTime.now(ZoneOffset.UTC), 10)).isEmpty();
        var publishedRow = row(id);

        assertThat(relay.publish(id)).isFalse();

        assertThat(row(id)).isEqualTo(publishedRow);
        verify(producer).publishAndAwait(request);
        assertRecords(request, 1);
    }

    @Test
    void 응답_시간_초과와_늦은_성공에도_PENDING을_보존하고_다음_명시적_발행에서_완료한다() throws Exception {
        var request = request();
        Long id = save(request);
        var before = row(id);
        var delayed = new CompletableFuture<SendResult<Object, Object>>();
        var actual = new AtomicReference<CompletableFuture<SendResult<Object, Object>>>();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            var future = (CompletableFuture<SendResult<Object, Object>>) invocation.callRealMethod();
            actual.set(future);
            return delayed;
        }).when(template()).send(eq(topic), eq(userId), eq(request));
        publishProperties.setResultTimeoutMs(100L);
        retryProperties.setInitialDelayMs(60_000);

        assertThatThrownBy(() -> relay.publish(id))
            .isInstanceOfSatisfying(NotificationBusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(NotificationErrorCode.OUTBOX_PUBLISH_FAILED))
            .hasCauseInstanceOf(TimeoutException.class);

        var retryRow = row(id);
        assertThat(outboxes.findById(id).orElseThrow().getFailureCount()).isEqualTo(1);
        assertThat(outboxes.findById(id).orElseThrow().getLastFailureType()).isEqualTo("PUBLISH_RESULT_TIMEOUT");
        var unchanged = new LinkedHashMap<>(before);
        unchanged.remove("failure_count");
        unchanged.remove("last_failure_type");
        unchanged.remove("next_attempt_at");
        assertThat(retryRow).containsAllEntriesOf(unchanged);
        assertThat(relay.publish(id)).isFalse();
        var success = actual.get().get(20, TimeUnit.SECONDS);
        assertThat(delayed.complete(success)).isTrue();
        assertThat(row(id)).isEqualTo(retryRow);
        verify(producer).publishAndAwait(request);
        assertRecords(request, 1);

        doCallRealMethod().when(template()).send(eq(topic), eq(userId), eq(request));
        publishProperties.setResultTimeoutMs(15_000L);
        jdbc.update("UPDATE notification_request_outbox SET next_attempt_at = ? WHERE id = ?",
            LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), id);
        assertThat(relay.publish(id)).isTrue();

        assertThat(outboxes.findById(id).orElseThrow().getStatus()).isEqualTo(NotificationOutboxStatus.PUBLISHED);
        verify(producer, times(2)).publishAndAwait(request);
        assertRecords(request, 2);
    }

    @Test
    void Kafka_성공_후_DB_갱신_실패는_기존_행을_보존하고_재발행_가능_구간으로_남는다() throws Exception {
        var request = request();
        Long id = save(request);
        var before = row(id);
        jdbc.execute("""
            ALTER TABLE notification_request_outbox ADD CONSTRAINT test_reject_outbox_published
            CHECK (status <> 'PUBLISHED')
            """);
        try {
            assertThatThrownBy(() -> relay.publish(id))
                .isInstanceOfSatisfying(NotificationBusinessException.class,
                    failure -> assertThat(failure.getErrorCode())
                        .isEqualTo(NotificationErrorCode.OUTBOX_STATE_UPDATE_FAILED))
                .hasCauseInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("test_reject_outbox_published");

            assertThat(row(id)).isEqualTo(before);
            verify(producer).publishAndAwait(request);
            assertRecords(request, 1);
            verify(failureReporter)
                .report(argThat(context -> context.errorCode() == NotificationFailureCode.OUTBOX_STATE_UPDATE_FAILED
                    && context.kafkaDetails().attempt() == null), eq(false));
        } finally {
            jdbc.execute("ALTER TABLE notification_request_outbox DROP CHECK test_reject_outbox_published");
        }

        assertThat(relay.publish(id)).isTrue();

        assertThat(outboxes.findById(id).orElseThrow().getStatus()).isEqualTo(NotificationOutboxStatus.PUBLISHED);
        verify(producer, times(2)).publishAndAwait(request);
        assertRecords(request, 2);
    }

    @Test
    void 업무_트랜잭션_안에서의_Relay_호출을_발행_전에_거부한다() {
        Long id = save(request());
        var before = row(id);

        transaction.executeWithoutResult(tx -> assertThatThrownBy(() -> relay.publish(id))
            .isInstanceOf(IllegalTransactionStateException.class));

        assertThat(row(id)).isEqualTo(before);
        verifyNoInteractions(producer);
    }

    @Test
    void 복원_불가_payload도_한도까지_기록한_뒤_FAILED로_종료하고_식별정보를_보고한다() throws Exception {
        var request = request();
        Long id = save(request);
        retryProperties.setMaxFailures(2);
        jdbc.update("UPDATE notification_request_outbox SET payload = '{}' WHERE id = ?", id);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var stored = outboxes.findById(id).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(NotificationOutboxStatus.FAILED);
            assertThat(stored.getFailureCount()).isEqualTo(2);
            return null;
        }).when(failureReporter).report(any(), eq(true));

        assertThatThrownBy(() -> relay.publish(id))
            .isInstanceOfSatisfying(NotificationBusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(NotificationErrorCode.OUTBOX_RESTORE_FAILED))
            .hasCauseInstanceOf(NotificationInvalidCreateRequestException.class);

        var first = outboxes.findById(id).orElseThrow();
        assertThat(first.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(first.getFailureCount()).isEqualTo(1);
        assertThat(first.getPayload()).isEqualTo("{}");
        assertThat(first.getLastFailureType()).isEqualTo("OUTBOX_RESTORE_FAILED");
        verify(failureReporter)
            .report(argThat(context -> context.errorCode() == NotificationFailureCode.OUTBOX_RESTORE_FAILED
                && context.sourceEventId().equals(request.sourceEventId()) && context.recipientUserId().equals(userId)
                && context.requestedAt() == null && context.kafkaDetails().attempt() == 1), eq(false));
        jdbc.update("UPDATE notification_request_outbox SET next_attempt_at = ? WHERE id = ?",
            LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), id);

        assertThatThrownBy(() -> relay.publish(id))
            .isInstanceOfSatisfying(NotificationBusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(NotificationErrorCode.OUTBOX_RESTORE_FAILED))
            .hasCauseInstanceOf(NotificationInvalidCreateRequestException.class);

        assertThat(outboxes.findById(id).orElseThrow().getStatus()).isEqualTo(NotificationOutboxStatus.FAILED);
        assertThat(outboxes.findById(id).orElseThrow().getFailureCount()).isEqualTo(2);
        assertThat(relay.publish(id)).isFalse();
        verify(failureReporter)
            .report(argThat(context -> context.errorCode() == NotificationFailureCode.OUTBOX_RETRY_LIMIT_EXCEEDED),
                eq(true));
        verifyNoInteractions(producer);
    }

    @Test
    void 권한_오류는_별도_확인_코드로_보고하고_한도_전까지_PENDING을_보존한다() {
        var request = request();
        Long id = save(request);
        var failure = new AuthorizationException("permission denied");
        doReturn(CompletableFuture.failedFuture(failure)).when(template()).send(eq(topic), eq(userId), eq(request));

        assertThatThrownBy(() -> relay.publish(id))
            .isInstanceOfSatisfying(NotificationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(NotificationErrorCode.OUTBOX_PUBLISH_FAILED))
            .hasCauseInstanceOf(java.util.concurrent.ExecutionException.class)
            .hasRootCauseInstanceOf(AuthorizationException.class);

        var stored = outboxes.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(stored.getFailureCount()).isEqualTo(1);
        assertThat(stored.getLastFailureType()).isEqualTo("OUTBOX_PUBLISH_REQUIRES_ATTENTION");
        assertThat(outboxService.restore(stored)).isEqualTo(request);
        verify(failureReporter)
            .report(argThat(context -> context.errorCode() == NotificationFailureCode.OUTBOX_PUBLISH_REQUIRES_ATTENTION
                && context.causeType().equals("AuthorizationException")
                && Boolean.TRUE.equals(context.kafkaDetails().retryable())), eq(false));
    }

    @Test
    void 실행_중단은_실패_횟수를_소진하지_않고_중단_상태와_요청을_보존한다() throws Exception {
        var request = request();
        Long id = save(request);
        var before = row(id);
        var failure = new InterruptedException();
        doThrow(failure).when(producer).publishAndAwait(request);

        assertThatThrownBy(() -> relay.publish(id))
            .isInstanceOfSatisfying(NotificationBusinessException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(NotificationErrorCode.OUTBOX_PUBLISH_INTERRUPTED);
                assertThat(exception.getMessage()).isNull();
                assertThat(exception.getCause()).isSameAs(failure);
            });

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
        assertThat(row(id)).isEqualTo(before);
        verify(failureReporter)
            .report(argThat(context -> context.errorCode() == NotificationFailureCode.PUBLISH_INTERRUPTED
                && context.kafkaDetails().attempt() == null), eq(false));
    }

    @Test
    void 독립된_두_Job은_처리_직전에만_확보하고_유효한_담당의_중복_발행을_막는다() throws Exception {
        var first = request();
        var second = request();
        Long firstId = save(first);
        Long secondId = save(second);
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown();
            if (!finish.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("TEST_WAIT_TIMEOUT");
            }
            return invocation.callRealMethod();
        }).when(producer).publishAndAwait(first);
        var firstRelay = independentRelay();
        var secondRelay = independentRelay();
        var firstJob = independentJob(firstRelay);
        var secondJob = independentJob(secondRelay);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(firstJob::publishDueRequests);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(row(firstId).get("processing_token")).isNotNull();
                assertThat(row(secondId).get("processing_token")).isNull();
                assertThat(secondRelay.publish(firstId)).isFalse();
                secondJob.publishDueRequests();
                assertThat(outboxes.findById(secondId).orElseThrow().getStatus())
                    .isEqualTo(NotificationOutboxStatus.PUBLISHED);
            } finally {
                finish.countDown();
            }
            running.get(10, TimeUnit.SECONDS);
        }
        assertThat(outboxes.findById(firstId).orElseThrow().getStatus()).isEqualTo(NotificationOutboxStatus.PUBLISHED);
        verify(producer).publishAndAwait(first);
        verify(producer).publishAndAwait(second);
        assertRecords(List.of(first, second));
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "timeout"})
    void 만료_후_재확보한_서버의_성공을_이전_서버의_늦은_결과가_덮어쓰지_못한다(String outcome) throws Exception {
        var request = request();
        Long id = save(request);
        jdbc.update("UPDATE notification_request_outbox SET failure_count = 4 WHERE id = ?", id);
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                if (!finish.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("TEST_WAIT_TIMEOUT");
                }
                if (outcome.equals("timeout")) {
                    throw new TimeoutException();
                }
            }
            return invocation.callRealMethod();
        }).when(producer).publishAndAwait(request);
        var first = independentRelay();
        var second = independentRelay();
        Map<String, Object> completed;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(() -> outcome(first, id));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                String old = (String) row(id).get("processing_token");
                expire(id);
                assertThat(second.publish(id)).isTrue();
                completed = row(id);
                assertThat(completed.get("processing_token")).isNull();
                assertThat(old).isNotNull();
            } finally {
                finish.countDown();
            }
            assertThat(running.get(10, TimeUnit.SECONDS)).isEqualTo(NotificationErrorCode.OUTBOX_CLAIM_LOST);
        }
        assertThat(row(id)).isEqualTo(completed);
        assertThat(outboxes.findById(id).orElseThrow().getFailureCount()).isEqualTo(4);
        verify(failureReporter)
            .report(argThat(context -> context.errorCode() == NotificationFailureCode.OUTBOX_CLAIM_LOST), eq(false));
        verify(failureReporter, never()).report(any(), eq(true));
        assertRecords(request, outcome.equals("success") ? 2 : 1);
    }

    @Test
    void 새_담당이_FAILED로_기록한_결과를_이전_담당의_늦은_성공이_덮어쓰지_못한다() throws Exception {
        var request = request();
        Long id = save(request);
        retryProperties.setMaxFailures(1);
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                if (!finish.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("TEST_WAIT_TIMEOUT");
                }
                return invocation.callRealMethod();
            }
            throw new TimeoutException();
        }).when(producer).publishAndAwait(request);
        var first = independentRelay();
        var second = independentRelay();
        Map<String, Object> failed;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(() -> outcome(first, id));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                expire(id);
                assertThat(outcome(second, id)).isEqualTo(NotificationErrorCode.OUTBOX_PUBLISH_FAILED);
                failed = row(id);
            } finally {
                finish.countDown();
            }
            assertThat(running.get(10, TimeUnit.SECONDS)).isEqualTo(NotificationErrorCode.OUTBOX_CLAIM_LOST);
        }
        assertThat(row(id)).isEqualTo(failed);
        assertThat(outboxes.findById(id).orElseThrow().getStatus()).isEqualTo(NotificationOutboxStatus.FAILED);
        assertThat(outboxes.findById(id).orElseThrow().getFailureCount()).isEqualTo(1);
        verify(failureReporter).report(
            argThat(context -> context.errorCode() == NotificationFailureCode.OUTBOX_RETRY_LIMIT_EXCEEDED), eq(true));
        assertRecords(request, 1);
    }

    @Test
    void 해제_DB_오류로_담당이_남아도_만료_후_다른_서버가_실패횟수_증가없이_복구한다() throws Exception {
        var request = request();
        Long id = save(request);
        jdbc.update("UPDATE notification_request_outbox SET failure_count = 4 WHERE id = ?", id);
        doAnswer(invocation -> {
            jdbc.execute(
                "ALTER TABLE notification_request_outbox ADD CONSTRAINT test_keep_processing_token CHECK (processing_token IS NOT NULL)");
            throw new InterruptedException();
        }).when(producer).publishAndAwait(request);
        try {
            assertThat(outcome(independentRelay(), id)).isEqualTo(NotificationErrorCode.OUTBOX_PUBLISH_INTERRUPTED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            Thread.interrupted();
            assertThat(row(id).get("processing_token")).isNotNull();
            assertThat(outboxes.findById(id).orElseThrow().getFailureCount()).isEqualTo(4);
            verify(failureReporter).report(
                argThat(context -> context.errorCode() == NotificationFailureCode.OUTBOX_RELEASE_FAILED), eq(false));
        } finally {
            Thread.interrupted();
            jdbc.execute("ALTER TABLE notification_request_outbox DROP CHECK test_keep_processing_token");
        }
        doCallRealMethod().when(producer).publishAndAwait(request);
        var second = independentRelay();
        assertThat(second.publish(id)).isFalse();
        expire(id);
        independentJob(second).publishDueRequests();
        var stored = outboxes.findById(id).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(NotificationOutboxStatus.PUBLISHED);
        assertThat(stored.getFailureCount()).isEqualTo(4);
        assertThat(stored.getProcessingToken()).isNull();
        assertRecords(request, 1);
    }

    @Test
    void 이전_실행의_중단_정리가_새_실행의_유효한_담당을_해제하지_못한다() throws Exception {
        var request = request();
        Long id = save(request);
        jdbc.update("UPDATE notification_request_outbox SET failure_count = 4 WHERE id = ?", id);
        var firstEntered = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var finishFirst = new CountDownLatch(1);
        var finishSecond = new CountDownLatch(1);
        var calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown();
                if (!finishFirst.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("TEST_WAIT_TIMEOUT");
                }
                throw new InterruptedException();
            }
            secondEntered.countDown();
            if (!finishSecond.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("TEST_WAIT_TIMEOUT");
            }
            return invocation.callRealMethod();
        }).when(producer).publishAndAwait(request);
        var first = independentRelay();
        var second = independentRelay();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var old = executor.submit(() -> {
                var result = outcome(first, id);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                return result;
            });
            try {
                assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
                String oldToken = (String) row(id).get("processing_token");
                expire(id);
                var current = executor.submit(() -> second.publish(id));
                assertThat(secondEntered.await(5, TimeUnit.SECONDS)).isTrue();
                var claimed = row(id);
                assertThat(claimed.get("processing_token")).isNotEqualTo(oldToken);
                finishFirst.countDown();
                assertThat(old.get(5, TimeUnit.SECONDS)).isEqualTo(NotificationErrorCode.OUTBOX_PUBLISH_INTERRUPTED);
                assertThat(row(id)).isEqualTo(claimed);
                finishSecond.countDown();
                assertThat(current.get(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                finishFirst.countDown();
                finishSecond.countDown();
            }
        }
        assertThat(outboxes.findById(id).orElseThrow().getFailureCount()).isEqualTo(4);
        verify(failureReporter, never()).report(any(), eq(true));
        assertRecords(request, 1);
    }

    private NotificationOutboxRelay independentRelay() {
        return new NotificationOutboxRelay(outboxService, claims, producer, failures,
            new NotificationOutboxFailureClassifier(), failureReporter);
    }

    private NotificationOutboxPublishJob independentJob(NotificationOutboxRelay instance) {
        var properties = new NotificationOutboxRelayProperties();
        properties.setEnabled(true);
        return new NotificationOutboxPublishJob(outboxService, instance, properties, failureReporter);
    }

    private NotificationErrorCode outcome(NotificationOutboxRelay instance, Long id) {
        try {
            instance.publish(id);
            return null;
        } catch (NotificationBusinessException failure) {
            return (NotificationErrorCode) failure.getErrorCode();
        }
    }

    private void expire(Long id) {
        jdbc.update(
            "UPDATE notification_request_outbox SET processing_until = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) WHERE id = ?",
            id);
    }

    private Long save(NotificationCreateRequested request) {
        return transaction.execute(tx -> outboxService.append(request).getId());
    }

    private NotificationCreateRequested request() {
        return new NotificationCreateRequested("LIKE_SENT:" + UUID.randomUUID(), userId, NotificationType.LIKE,
            "원본 제목", "하늘님이 당신에게 마음을 전했어요.", "PROFILE", 202L, REQUESTED_AT);
    }

    private Map<String, Object> row(Long id) {
        return jdbc.queryForMap("SELECT * FROM notification_request_outbox WHERE id = ?", id);
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<Object, Object> template() {
        return (KafkaTemplate<Object, Object>) kafkaTemplate;
    }

    private void assertRecords(NotificationCreateRequested request, int expectedCount) {
        assertRecords(java.util.Collections.nCopies(expectedCount, request));
    }

    private void assertRecords(List<NotificationCreateRequested> expected) {
        int expectedCount = expected.size();
        Map<String, Object> properties = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "outbox-relay-" + UUID.randomUUID(),
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, LongDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        var partitions = List.of(new TopicPartition(topic, 0), new TopicPartition(topic, 1));
        try (var consumer = new KafkaConsumer<Long, String>(properties)) {
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            var records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(20), expectedCount);
            assertThat(records.count()).isEqualTo(expectedCount);
            var actual = new ArrayList<NotificationCreateRequested>();
            for (var record : records) {
                assertThat(record.topic()).isEqualTo(topic);
                assertThat(record.key()).isEqualTo(userId);
                assertThat(record.headers().lastHeader("__TypeId__")).isNull();
                actual.add(jsonMapper.readValue(record.value(), NotificationCreateRequested.class));
            }
            assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(consumer.endOffsets(partitions, Duration.ofSeconds(20)).values().stream()
                .mapToLong(Long::longValue).sum()).isEqualTo(expectedCount);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({NotificationKafkaProperties.class, NotificationPublishProperties.class})
    static class KafkaConfiguration {

        @Bean
        DefaultKafkaProducerFactoryCustomizer kafkaProducerFactoryCustomizer(JsonMapper mapper) {
            return new KafkaProducerConfiguration().kafkaProducerFactoryCustomizer(mapper);
        }
    }
}
