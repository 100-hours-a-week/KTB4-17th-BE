package com.team.dating_backend.notification.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.alert.DiscordWebhookClient;
import com.team.dating_backend.notification.alert.NotificationFailureContext;
import com.team.dating_backend.notification.alert.NotificationFailureContext.KafkaLocation;
import com.team.dating_backend.notification.config.NotificationCreationProperties;
import com.team.dating_backend.notification.config.NotificationKafkaConfiguration;
import com.team.dating_backend.notification.config.NotificationKafkaProperties;
import com.team.dating_backend.notification.config.NotificationRetryConfiguration;
import com.team.dating_backend.notification.config.NotificationRetryProperties;
import com.team.dating_backend.common.config.SchedulingConfig;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.repository.NotificationRepository;
import com.team.dating_backend.notification.service.NotificationCreateRequestedValidator;
import com.team.dating_backend.notification.service.NotificationCreationProcessor;
import com.team.dating_backend.notification.service.NotificationCreationService;
import com.team.dating_backend.notification.service.NotificationLookupService;
import com.team.dating_backend.notification.service.NotificationPersistenceService;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    classes = NotificationCreationPipelineIntegrationTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.config.name=notification-creation-pipeline-test",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.producer.acks=all",
        "spring.kafka.producer.properties.enable.idempotence=true",
        "spring.kafka.producer.properties.spring.json.add.type.headers=false",
        "spring.kafka.consumer.enable-auto-commit=false",
        "spring.kafka.consumer.properties.allow.auto.create.topics=false",
        "app.notification.creation.enabled=false",
        "app.notification.retry.max-attempts=2",
        "app.notification.retry.initial-delay-ms=100",
        "app.notification.retry.max-delay-ms=1000",
        "app.notification.retry.publish-result-timeout-ms=5000"
    }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Testcontainers
class NotificationCreationPipelineIntegrationTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-10-22T01:02:03.123456789Z");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 22, 1, 2, 3, 123456000);

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @DynamicPropertySource
    static void topicProperties(DynamicPropertyRegistry registry) {
        String suffix = UUID.randomUUID().toString();
        registry.add("app.notification.kafka.create-requested-topic", () -> "pipeline-create-" + suffix);
        registry.add("app.notification.kafka.delivery-topic", () -> "pipeline-delivery-" + suffix);
        registry.add("app.notification.creation.group-id", () -> "pipeline-group-" + suffix);
    }

    @Autowired
    private NotificationKafkaProperties topics;

    @Autowired
    private NotificationCreationProperties properties;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private KafkaTemplate<Object, Object> template;

    @Autowired
    private NotificationCreationService creationService;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private UserRepository users;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private DiscordWebhookClient webhookClient;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoSpyBean
    private NotificationDeliveryProducer deliveryProducer;

    @MockitoSpyBean
    private NotificationPersistenceService persistenceService;

    private Admin admin;
    private KafkaConsumer<String, String> deliveryConsumer;
    private ConcurrentMessageListenerContainer<?, ?> container;
    private TransactionTemplate transaction;
    private Long userId;
    private String sourceEventId;

    @BeforeEach
    void setUp() throws Exception {
        transaction = new TransactionTemplate(transactionManager);
        userId = transaction.execute(status -> {
            User user = User.create("수신자", LocalDate.of(2000, 1, 1), Gender.FEMALE, CREATED_AT);
            user.activate(CREATED_AT);
            return users.saveAndFlush(user).getId();
        });
        sourceEventId = UUID.randomUUID().toString();
        admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
        admin.createTopics(List.of(new NewTopic(topics.getCreateRequestedTopic(), 1, (short) 1),
            new NewTopic(topics.getCreateRequestedTopic() + "-retry-0", 1, (short) 1),
            new NewTopic(topics.getCreateRequestedTopic() + "-dlt", 1, (short) 1),
            new NewTopic(topics.getDeliveryTopic(), 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
        deliveryConsumer = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
        deliveryConsumer.assign(List.of(deliveryPartition()));
        deliveryConsumer.seekToBeginning(List.of(deliveryPartition()));
        container = (ConcurrentMessageListenerContainer<?, ?>) registry
            .getListenerContainer("notificationCreationConsumer");
    }

    @AfterEach
    void cleanUp() {
        if (registry != null) {
            registry.stop();
        }
        if (deliveryConsumer != null) {
            deliveryConsumer.close();
        }
        if (admin != null) {
            admin.close();
        }
        if (userId != null) {
            transaction.executeWithoutResult(status -> {
                jdbc.update("DELETE FROM notifications WHERE user_id = ?", userId);
                jdbc.update("DELETE FROM users WHERE id = ?", userId);
            });
        }
    }

    @Test
    void 정상_생성과_중복_요청은_DB_한_행과_기존_내용을_유지하고_delivery_후_commit한다() throws Exception {
        startConsumers();
        send(request("원본 제목", REQUESTED_AT));
        awaitCommitted(1L);
        var first = readDelivery(1).getFirst();
        Long notificationId = first.notification().notificationId();
        assertThat(rowCount()).isEqualTo(1L);
        assertThat(first.notification().createdAt()).isEqualTo(CREATED_AT);
        transaction.executeWithoutResult(status -> notifications.findById(notificationId).orElseThrow()
            .markAsRead(CREATED_AT.plusMinutes(1)));
        Map<String, Object> before = storedRow();

        send(request("재처리 제목", REQUESTED_AT.plusSeconds(3600)));
        awaitCommitted(2L);
        var replay = readDelivery(1).getFirst();

        assertThat(storedRow()).isEqualTo(before);
        assertThat(rowCount()).isEqualTo(1L);
        assertThat(replay.notification().notificationId()).isEqualTo(notificationId);
        assertThat(replay.notification().title()).isEqualTo("원본 제목");
        assertThat(replay.notification().createdAt()).isEqualTo(CREATED_AT);
        assertThat(replay.notification().readAt()).isEqualTo(CREATED_AT.plusMinutes(1));
        assertThat(deliveryEndOffset()).isEqualTo(2L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void DB_commit_후_두_실패_구간은_Retry로_복구되고_기존_ID를_보존한다(boolean afterDelivery) throws Exception {
        var firstAttempt = new AtomicBoolean(true);
        var transactionActive = new AtomicBoolean(true);
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (!firstAttempt.getAndSet(false)) {
                return invocation.callRealMethod();
            }
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            if (afterDelivery) {
                invocation.callRealMethod();
            }
            reached.countDown();
            if (!release.await(20, TimeUnit.SECONDS)) {
                throw new TimeoutException();
            }
            throw new TimeoutException();
        }).when(deliveryProducer).publishAndAwait(any());
        startConsumers();
        send(request("원본 제목", REQUESTED_AT));
        Map<String, Object> committedRow;
        try {
            assertThat(reached.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(transactionActive).isFalse();
            committedRow = storedRow();
            assertThat(rowCount()).isEqualTo(1L);
            assertThat(committedRow).containsEntry("title", "원본 제목");
            assertThat(committedOffset()).isNull();
            assertThat(deliveryEndOffset()).isEqualTo(afterDelivery ? 1L : 0L);
        } finally {
            release.countDown();
        }
        var deliveries = readDelivery(afterDelivery ? 2 : 1);
        awaitCommitted(1L);

        assertThat(container.isRunning()).isTrue();
        assertThat(rowCount()).isEqualTo(1L);
        assertThat(storedRow()).isEqualTo(committedRow);
        assertThat(deliveries).allSatisfy(event -> {
            assertThat(event.notification().notificationId()).isEqualTo(((Number) committedRow.get("id")).longValue());
            assertThat(event.notification().title()).isEqualTo("원본 제목");
            assertThat(event.notification().createdAt()).isEqualTo(CREATED_AT);
        });
        assertThat(deliveryEndOffset()).isEqualTo(afterDelivery ? 2L : 1L);
        if (afterDelivery) {
            assertThat(deliveries.getFirst()).isEqualTo(deliveries.getLast());
        }
    }

    @Test
    void DB_commit_전_실패는_rollback한_뒤_Retry에서_한번_저장하고_통보하지_않는다() throws Exception {
        var firstAttempt = new AtomicBoolean(true);
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            boolean first = firstAttempt.getAndSet(false);
            if (!first) {
                assertThat(rowCount()).isZero();
            }
            Object result = invocation.callRealMethod();
            if (first) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(rowCount()).isEqualTo(1L);
                reached.countDown();
                if (!release.await(20, TimeUnit.SECONDS)) {
                    throw new TimeoutException();
                }
                throw new TransientDataAccessResourceException(null);
            }
            return result;
        }).when(persistenceService).create(any());
        startConsumers();
        send(request("원본 제목", REQUESTED_AT));
        try {
            assertThat(reached.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(rowCount()).isZero();
            assertThat(committedOffset()).isNull();
            assertThat(deliveryEndOffset()).isZero();
        } finally {
            release.countDown();
        }

        var delivered = readDelivery(1).getFirst();
        awaitRetryCommitted();
        awaitCommitted(1L);

        assertThat(rowCount()).isEqualTo(1L);
        assertThat(storedRow()).containsEntry("title", "원본 제목").containsEntry("body", "원본 본문");
        assertThat(delivered.notification().notificationId()).isEqualTo(((Number) storedRow().get("id")).longValue());
        assertThat(delivered.notification().createdAt()).isEqualTo(CREATED_AT);
        assertThat(endOffset(topics.getCreateRequestedTopic() + "-retry-0")).isEqualTo(1L);
        assertThat(endOffset(topics.getCreateRequestedTopic() + "-dlt")).isZero();
        verifyNoInteractions(webhookClient);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void DB가_있는_요청의_Retry_소진은_통보_결과와_무관하게_DLT와_commit을_보존한다(boolean webhookFails) throws Exception {
        var attempts = new CopyOnWriteArrayList<NotificationDeliveryEvent>();
        var firstDelivery = new CountDownLatch(1);
        var releaseDelivery = new CountDownLatch(1);
        var reporting = new CountDownLatch(1);
        var releaseReporting = new CountDownLatch(1);
        var reported = new AtomicReference<NotificationFailureContext>();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            attempts.add(invocation.getArgument(0));
            if (attempts.size() == 1) {
                firstDelivery.countDown();
                if (!releaseDelivery.await(20, TimeUnit.SECONDS)) {
                    throw new TimeoutException();
                }
            }
            throw new TimeoutException();
        }).when(deliveryProducer).publishAndAwait(any());
        doAnswer(invocation -> {
            reported.set(invocation.getArgument(0));
            reporting.countDown();
            if (!releaseReporting.await(20, TimeUnit.SECONDS)) {
                throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }
            if (webhookFails) {
                throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }
            return null;
        }).when(webhookClient).send(any());
        var original = request("원본 제목", REQUESTED_AT);
        Map<String, Object> committedRow;
        Long notificationId;
        try {
            startConsumers();
            send(original);
            assertThat(firstDelivery.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(rowCount()).isEqualTo(1L);
            notificationId = ((Number) storedRow().get("id")).longValue();
            transaction.executeWithoutResult(status -> notifications.findById(notificationId).orElseThrow()
                .markAsRead(CREATED_AT.plusMinutes(1)));
            committedRow = storedRow();
            assertThat(committedOffset()).isNull();
            verifyNoInteractions(webhookClient);
            releaseDelivery.countDown();

            assertThat(reporting.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(rowCount()).isEqualTo(1L);
            assertThat(storedRow()).isEqualTo(committedRow);
            assertThat(retryCommittedOffset()).isNull();
            assertThat(endOffset(topics.getCreateRequestedTopic() + "-dlt")).isEqualTo(1L);
            assertThat(deliveryEndOffset()).isZero();
            var failure = reported.get();
            assertThat(failure.errorCode()).isEqualTo(NotificationFailureCode.DLT_RECORDED);
            assertThat(failure.sourceEventId()).isEqualTo(sourceEventId);
            assertThat(failure.recipientUserId()).isEqualTo(userId);
            assertThat(failure.type()).isEqualTo(original.type());
            assertThat(failure.requestedAt()).isEqualTo(REQUESTED_AT);
            var details = failure.kafkaDetails();
            assertThat(details.consumerGroup()).isEqualTo(properties.getGroupId() + "-retry-0");
            assertThat(details.originalLocation())
                .isEqualTo(new KafkaLocation(topics.getCreateRequestedTopic(), 0, 0L));
            assertThat(details.inputLocation())
                .isEqualTo(new KafkaLocation(topics.getCreateRequestedTopic() + "-retry-0", 0, 0L));
            assertThat(details.destinationLocation())
                .isEqualTo(new KafkaLocation(topics.getCreateRequestedTopic() + "-dlt", 0, 0L));
            assertThat(details.attempt()).isEqualTo(2);
            assertThat(details.retryable()).isTrue();
            assertThat(details.processingExceptionType()).isEqualTo("TimeoutException");
            assertThat(details.processingCauseType()).isNull();
            var deadLetter = readDeadLetter();
            assertThat(deadLetter.key()).isEqualTo(userId.toString());
            assertThat(deadLetter.offset()).isEqualTo(details.destinationLocation().offset());
            assertThat(jsonMapper.readValue(deadLetter.value(), NotificationCreateRequested.class)).isEqualTo(original);
        } finally {
            releaseDelivery.countDown();
            releaseReporting.countDown();
        }
        awaitRetryCommitted();
        awaitCommitted(1L);

        assertThat(rowCount()).isEqualTo(1L);
        assertThat(storedRow()).isEqualTo(committedRow);
        assertThat(attempts).hasSize(2).allSatisfy(event -> {
            assertThat(event.sourceEventId()).isEqualTo(sourceEventId);
            assertThat(event.notification().notificationId()).isEqualTo(notificationId);
            assertThat(event.notification().title()).isEqualTo("원본 제목");
            assertThat(event.notification().body()).isEqualTo("원본 본문");
            assertThat(event.notification().createdAt()).isEqualTo(CREATED_AT);
        });
        assertThat(attempts.getLast().notification().readAt()).isEqualTo(CREATED_AT.plusMinutes(1));
        assertThat(endOffset(topics.getCreateRequestedTopic() + "-dlt")).isEqualTo(1L);
        verify(webhookClient).send(reported.get());
        assertThat(meterRegistry.get("notification.failures").tag("error_code", "DLT_RECORDED").counter().count())
            .isEqualTo(1);
        assertThat(meterRegistry.get("notification.alert.sent").counter().count()).isEqualTo(webhookFails ? 0 : 1);
        if (webhookFails) {
            assertThat(meterRegistry.get("notification.alert.failures")
                .tag("error_code", "ALERT_WEBHOOK_FAILED").counter().count()).isEqualTo(1);
        } else {
            assertThat(meterRegistry.find("notification.alert.failures").counter()).isNull();
        }
    }

    @Test
    void 삭제된_알림의_재요청은_DB_상태를_보존하고_delivery_없이_commit한다() throws Exception {
        Long notificationId = creationService.createOrLoad(request("원본 제목", REQUESTED_AT))
            .notification().notificationId();
        transaction.executeWithoutResult(status -> {
            var notification = notifications.findById(notificationId).orElseThrow();
            notification.markAsRead(CREATED_AT.plusMinutes(1));
            notification.markAsDeleted(CREATED_AT.plusMinutes(2));
        });
        Map<String, Object> before = storedRow();

        startConsumers();
        send(request("재처리 제목", REQUESTED_AT.plusSeconds(3600)));
        awaitCommitted(1L);

        assertThat(rowCount()).isEqualTo(1L);
        assertThat(storedRow()).isEqualTo(before);
        assertThat(storedRow().get("deleted_at")).isNotNull();
        assertThat(deliveryEndOffset()).isZero();
        verifyNoInteractions(deliveryProducer);
    }

    private void startConsumers() {
        registry.getListenerContainers().forEach(listener -> {
            if (!List.of(listener.getContainerProperties().getTopics())
                .contains(topics.getCreateRequestedTopic() + "-dlt")) {
                listener.start();
            }
        });
    }

    private void send(NotificationCreateRequested request) throws Exception {
        template.send(topics.getCreateRequestedTopic(), 0, userId.toString(), request).get(15, TimeUnit.SECONDS);
    }

    private NotificationCreateRequested request(String title, Instant requestedAt) {
        return new NotificationCreateRequested(1, sourceEventId, userId, NotificationType.LIKE,
            title, "원본 본문", "USER", 99L, requestedAt);
    }

    private void awaitCommitted(long offset) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(committedOffset()).isEqualTo(offset));
    }

    private Long committedOffset() throws Exception {
        var offset = admin.listConsumerGroupOffsets(properties.getGroupId()).partitionsToOffsetAndMetadata()
            .get(10, TimeUnit.SECONDS).get(new TopicPartition(topics.getCreateRequestedTopic(), 0));
        return offset == null ? null : offset.offset();
    }

    private void awaitRetryCommitted() {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(retryCommittedOffset()).isEqualTo(1L));
    }

    private Long retryCommittedOffset() throws Exception {
        var offset = admin.listConsumerGroupOffsets(properties.getGroupId() + "-retry-0")
            .partitionsToOffsetAndMetadata()
            .get(10, TimeUnit.SECONDS).get(new TopicPartition(topics.getCreateRequestedTopic() + "-retry-0", 0));
        return offset == null ? null : offset.offset();
    }

    private long endOffset(String topic) throws Exception {
        var partition = new TopicPartition(topic, 0);
        return admin.listOffsets(Map.of(partition, OffsetSpec.latest()))
            .partitionResult(partition).get(10, TimeUnit.SECONDS).offset();
    }

    private ConsumerRecord<String, String> readDeadLetter() {
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false))) {
            var partition = new TopicPartition(topics.getCreateRequestedTopic() + "-dlt", 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            var records = new ArrayList<ConsumerRecord<String, String>>();
            await().atMost(Duration.ofSeconds(15)).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
                return !records.isEmpty();
            });
            assertThat(records).hasSize(1);
            return records.getFirst();
        }
    }

    private long deliveryEndOffset() throws Exception {
        return admin.listOffsets(Map.of(deliveryPartition(), OffsetSpec.latest()))
            .partitionResult(deliveryPartition()).get(10, TimeUnit.SECONDS).offset();
    }

    private TopicPartition deliveryPartition() {
        return new TopicPartition(topics.getDeliveryTopic(), 0);
    }

    private List<NotificationDeliveryEvent> readDelivery(int expectedCount) throws Exception {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (records.size() < expectedCount && System.nanoTime() < deadline) {
            deliveryConsumer.poll(Duration.ofMillis(200)).forEach(records::add);
        }
        assertThat(records).hasSize(expectedCount);
        List<NotificationDeliveryEvent> events = new ArrayList<>();
        for (var record : records) {
            assertThat(record.key()).isEqualTo(userId.toString());
            assertThat(record.headers().lastHeader("__TypeId__")).isNull();
            var event = jsonMapper.readValue(record.value(), NotificationDeliveryEvent.class);
            assertThat(event.sourceEventId()).isEqualTo(sourceEventId);
            assertThat(event.recipientUserId()).isEqualTo(userId);
            events.add(event);
        }
        return events;
    }

    private Map<String, Object> storedRow() {
        return jdbc.queryForMap("SELECT * FROM notifications WHERE user_id = ? AND source_event_id = ?",
            userId, sourceEventId);
    }

    private long rowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ?", Long.class, userId);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan("com.team.dating_backend")
    @EnableJpaRepositories(basePackageClasses = {NotificationRepository.class, UserRepository.class})
    @EnableConfigurationProperties(
        {NotificationKafkaProperties.class, NotificationCreationProperties.class,
            NotificationRetryProperties.class}
    )
    @Import(
        {SchedulingConfig.class, NotificationKafkaConfiguration.class, NotificationCreationConsumer.class,
            NotificationCreationProcessor.class, NotificationCreateRequestedValidator.class,
            NotificationCreationService.class, NotificationPersistenceService.class, NotificationLookupService.class,
            NotificationDeliveryProducer.class, NotificationCreationFailureClassifier.class,
            NotificationRetryConfiguration.class, NotificationRecoveryTestConfiguration.class}
    )
    static class TestApplication {}
}
