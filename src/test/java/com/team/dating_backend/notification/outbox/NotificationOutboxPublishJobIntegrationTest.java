package com.team.dating_backend.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.notification.alert.NotificationFailureReporter;
import com.team.dating_backend.common.kafka.config.KafkaProducerConfiguration;
import com.team.dating_backend.notification.config.NotificationKafkaProperties;
import com.team.dating_backend.notification.config.NotificationOutboxRelayConfiguration;
import com.team.dating_backend.notification.config.NotificationOutboxRelayProperties;
import com.team.dating_backend.notification.config.NotificationOutboxRetryProperties;
import com.team.dating_backend.notification.config.NotificationPublishProperties;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationOutboxStatus;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.kafka.NotificationRequestProducer;
import com.team.dating_backend.notification.repository.NotificationRequestOutboxRepository;
import com.team.dating_backend.notification.service.NotificationCreateRequestedValidator;
import com.team.dating_backend.notification.service.NotificationOutboxFailureService;
import com.team.dating_backend.notification.service.NotificationOutboxClaimService;
import com.team.dating_backend.notification.service.NotificationRequestOutboxService;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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

@DataJpaTest(
    properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
        "app.notification.outbox.relay.enabled=true", "app.notification.outbox.relay.batch-size=2",
        "app.notification.outbox.relay.fixed-delay-ms=3600000"}
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ImportAutoConfiguration({KafkaAutoConfiguration.class, JacksonAutoConfiguration.class})
@Import(
    {NotificationOutboxPublishJob.class, NotificationOutboxRelayConfiguration.class,
        NotificationOutboxRelay.class, NotificationRequestOutboxService.class, NotificationRequestOutboxCodec.class,
        NotificationCreateRequestedValidator.class, NotificationRequestProducer.class,
        NotificationOutboxClaimService.class, NotificationOutboxFailureService.class,
        NotificationOutboxRetryPolicy.class,
        NotificationOutboxFailureClassifier.class, NotificationOutboxPublishJobIntegrationTest.KafkaConfiguration.class}
)
@Testcontainers
class NotificationOutboxPublishJobIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @Autowired
    private NotificationOutboxPublishJob job;
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
    private NotificationOutboxRetryProperties retryProperties;
    @Autowired
    @Qualifier("notificationOutboxScheduler")
    private ThreadPoolTaskScheduler scheduler;
    @Autowired
    private JsonMapper jsonMapper;
    @MockitoBean
    private NotificationFailureReporter reporter;
    @MockitoBean(name = "notificationAlertExecutor")
    private Executor alertExecutor;
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
        userId = transaction.execute(tx -> users.saveAndFlush(User.create("회원", LocalDate.of(2000, 1, 1),
            Gender.FEMALE, LocalDateTime.now(ZoneOffset.UTC))).getId());
        topic = "notification-outbox-job-" + UUID.randomUUID();
        kafkaProperties.setCreateRequestedTopic(topic);
        try (
            var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
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
    void 한_실행은_배치_크기만큼_발행하고_미래와_완료와_종료_요청은_제외한다() throws Exception {
        var first = request();
        var second = request();
        var third = request();
        var firstId = save(first);
        var secondId = save(second);
        var thirdId = save(third);
        var futureId = save(request());
        var publishedId = save(request());
        var failedId = save(request());
        jdbc.update("UPDATE notification_request_outbox SET next_attempt_at = ? WHERE id = ?",
            LocalDateTime.now(ZoneOffset.UTC).plusHours(1), futureId);
        jdbc.update("UPDATE notification_request_outbox SET status = 'PUBLISHED', published_at = ? WHERE id = ?",
            LocalDateTime.now(ZoneOffset.UTC), publishedId);
        jdbc.update("UPDATE notification_request_outbox SET status = 'FAILED', failure_count = 5 WHERE id = ?",
            failedId);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(producer).publishAndAwait(any());

        job.publishDueRequests();

        assertThat(status(firstId)).isEqualTo(NotificationOutboxStatus.PUBLISHED);
        assertThat(status(secondId)).isEqualTo(NotificationOutboxStatus.PUBLISHED);
        assertThat(status(thirdId)).isEqualTo(NotificationOutboxStatus.PENDING);
        assertRecords(List.of(first, second));

        job.publishDueRequests();

        assertThat(status(thirdId)).isEqualTo(NotificationOutboxStatus.PUBLISHED);
        assertThat(status(futureId)).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(status(failedId)).isEqualTo(NotificationOutboxStatus.FAILED);
        verify(producer, times(3)).publishAndAwait(any());
        assertRecords(List.of(first, second, third));
    }

    @Test
    void 첫_요청의_실패에도_다음을_발행하고_실패한_요청은_시각과_한도를_준수한다() throws Exception {
        var failed = request();
        var healthy = request();
        var failedId = save(failed);
        var healthyId = save(healthy);
        retryProperties.setMaxFailures(2);
        retryProperties.setInitialDelayMs(60_000);
        doAnswer(invocation -> CompletableFuture.failedFuture(new IllegalStateException()))
            .when(template()).send(eq(topic), eq(userId), eq(failed));

        job.publishDueRequests();

        assertThat(status(healthyId)).isEqualTo(NotificationOutboxStatus.PUBLISHED);
        assertThat(status(failedId)).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(outboxes.findById(failedId).orElseThrow().getFailureCount()).isEqualTo(1);
        job.publishDueRequests();
        verify(producer).publishAndAwait(failed);

        jdbc.update("UPDATE notification_request_outbox SET next_attempt_at = ? WHERE id = ?",
            LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), failedId);
        job.publishDueRequests();
        job.publishDueRequests();

        assertThat(status(failedId)).isEqualTo(NotificationOutboxStatus.FAILED);
        assertThat(outboxes.findById(failedId).orElseThrow().getFailureCount()).isEqualTo(2);
        verify(producer, times(2)).publishAndAwait(failed);
        assertRecords(List.of(healthy));
    }

    @Test
    void 실제_스케줄러는_저장된_요청의_실패와_대기_후_재발행을_연결한다() throws Exception {
        var request = request();
        var id = save(request);
        retryProperties.setInitialDelayMs(200);
        retryProperties.setMaxDelayMs(200);
        var calls = new AtomicInteger();
        var failedAt = new AtomicLong();
        var retriedAt = new AtomicLong();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                failedAt.set(System.nanoTime());
                throw new TimeoutException();
            }
            retriedAt.set(System.nanoTime());
            return invocation.callRealMethod();
        }).when(producer).publishAndAwait(request);
        var future = scheduler.scheduleWithFixedDelay(job::publishDueRequests, Duration.ofMillis(25));
        try {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(
                () -> assertThat(status(id)).isEqualTo(NotificationOutboxStatus.PUBLISHED));
        } finally {
            future.cancel(false);
        }

        var stored = outboxes.findById(id).orElseThrow();
        assertThat(stored.getFailureCount()).isEqualTo(1);
        assertThat(stored.getLastFailureType()).isEqualTo("PUBLISH_RESULT_TIMEOUT");
        assertThat(outboxService.restore(stored)).isEqualTo(request);
        assertThat(retriedAt.get() - failedAt.get()).isGreaterThanOrEqualTo(Duration.ofMillis(200).toNanos());
        verify(producer, times(2)).publishAndAwait(request);
        assertRecords(List.of(request));
    }

    @Test
    void 스케줄러_종료는_응답_대기를_중단하고_현재와_후속_Outbox를_변경하지_않는다() throws Exception {
        var first = request();
        var second = request();
        var firstId = save(first);
        var secondId = save(second);
        jdbc.update("UPDATE notification_request_outbox SET failure_count = 4 WHERE id = ?", firstId);
        var before = jdbc.queryForList("SELECT * FROM notification_request_outbox ORDER BY id");
        var waiting = new CountDownLatch(1);
        var pendingResult = new CompletableFuture<SendResult<Object, Object>>();
        doAnswer(invocation -> {
            waiting.countDown();
            return pendingResult;
        }).when(template()).send(eq(topic), eq(userId), eq(first));
        var properties = new NotificationOutboxRelayProperties();
        properties.setShutdownWaitSeconds(2);
        var stoppingScheduler = new NotificationOutboxRelayConfiguration().notificationOutboxScheduler(properties);
        stoppingScheduler.initialize();
        try {
            stoppingScheduler.schedule(job::publishDueRequests, Instant.now());
            assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            stoppingScheduler.shutdown();
        }

        assertThat(stoppingScheduler.getScheduledThreadPoolExecutor().isTerminated()).isTrue();
        assertThat(jdbc.queryForList("SELECT * FROM notification_request_outbox ORDER BY id")).isEqualTo(before);
        assertThat(status(firstId)).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(status(secondId)).isEqualTo(NotificationOutboxStatus.PENDING);
        verify(producer, never()).publishAndAwait(second);
    }

    @Test
    void 업무_트랜잭션에서는_배치_조회_전에_거부한다() {
        var id = save(request());

        transaction.executeWithoutResult(tx -> assertThatThrownBy(job::publishDueRequests)
            .isInstanceOf(IllegalTransactionStateException.class));

        assertThat(status(id)).isEqualTo(NotificationOutboxStatus.PENDING);
        verifyNoInteractions(producer);
    }

    private NotificationCreateRequested request() {
        return new NotificationCreateRequested(1, "LIKE_SENT:" + UUID.randomUUID(), userId, NotificationType.LIKE,
            "원본 제목", "원본 본문", "PROFILE", 202L, Instant.parse("2026-10-30T01:02:03.123456789Z"));
    }

    private Long save(NotificationCreateRequested request) {
        return transaction.execute(tx -> outboxService.append(request).getId());
    }

    private NotificationOutboxStatus status(Long id) {
        return outboxes.findById(id).orElseThrow().getStatus();
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<Object, Object> template() {
        return (KafkaTemplate<Object, Object>) kafkaTemplate;
    }

    private void assertRecords(List<NotificationCreateRequested> expected) {
        Map<String, Object> properties = Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "outbox-job-" + UUID.randomUUID(),
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false, ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, LongDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        var partition = new TopicPartition(topic, 0);
        try (var consumer = new KafkaConsumer<Long, String>(properties)) {
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            var records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(20), expected.size());
            assertThat(records.count()).isEqualTo(expected.size());
            var actual = records.records(partition).stream().map(record -> {
                assertThat(record.key()).isEqualTo(userId);
                assertThat(record.headers().lastHeader("__TypeId__")).isNull();
                return jsonMapper.readValue(record.value(), NotificationCreateRequested.class);
            }).toList();
            assertThat(actual).containsExactlyElementsOf(expected);
            assertThat(consumer.endOffsets(List.of(partition)).get(partition)).isEqualTo((long) expected.size());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(
        {NotificationKafkaProperties.class, NotificationPublishProperties.class,
            NotificationOutboxRelayProperties.class}
    )
    static class KafkaConfiguration {

        @Bean
        DefaultKafkaProducerFactoryCustomizer kafkaProducerFactoryCustomizer(JsonMapper mapper) {
            return new KafkaProducerConfiguration().kafkaProducerFactoryCustomizer(mapper);
        }
    }
}
