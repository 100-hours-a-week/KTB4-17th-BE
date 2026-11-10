package com.team.dating_backend.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.team.dating_backend.common.kafka.config.KafkaProducerConfiguration;
import com.team.dating_backend.notification.config.NotificationDeliveryProperties;
import com.team.dating_backend.notification.config.NotificationKafkaProperties;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.enums.NotificationOutboxStatus;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.exception.NotificationInvalidCreateRequestException;
import com.team.dating_backend.notification.kafka.NotificationDeliveryProducer;
import com.team.dating_backend.notification.service.NotificationCreateRequestedValidator;
import com.team.dating_backend.notification.service.NotificationCreationProcessor;
import com.team.dating_backend.notification.service.NotificationCreationService;
import com.team.dating_backend.notification.service.NotificationLookupService;
import com.team.dating_backend.notification.service.NotificationOutboxClaimService;
import com.team.dating_backend.notification.service.NotificationPersistenceService;
import com.team.dating_backend.notification.service.NotificationRequestOutboxService;
import com.team.dating_backend.user.entity.User;
import com.team.dating_backend.user.enums.Gender;
import com.team.dating_backend.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
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
        "app.notification.kafka.create-requested-topic=unused-worker-create",
        "app.notification.kafka.delivery-topic=worker-delivery"}
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ImportAutoConfiguration({KafkaAutoConfiguration.class, JacksonAutoConfiguration.class})
@Import(
    {NotificationOutboxWorker.class, NotificationRequestOutboxService.class, NotificationRequestOutboxCodec.class,
        NotificationCreateRequestedValidator.class, NotificationCreationProcessor.class,
        NotificationCreationService.class,
        NotificationPersistenceService.class, NotificationLookupService.class, NotificationOutboxClaimService.class,
        NotificationDeliveryProducer.class, KafkaProducerConfiguration.class,
        NotificationOutboxWorkerIntegrationTest.PropertiesConfiguration.class}
)
@Testcontainers
class NotificationOutboxWorkerIntegrationTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-11-09T01:02:03.123456789Z");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 11, 9, 1, 2, 3, 123456000);

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @Autowired
    private NotificationOutboxWorker worker;
    @Autowired
    private NotificationOutboxClaimService claims;
    @Autowired
    private NotificationCreationService creationService;
    @Autowired
    private NotificationRequestOutboxService outboxService;
    @Autowired
    private NotificationKafkaProperties topics;
    @Autowired
    private UserRepository users;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JsonMapper json;
    @MockitoSpyBean
    private NotificationDeliveryProducer producer;

    private TransactionTemplate transaction;
    private NotificationCreateRequested request;
    private NotificationRequestOutbox claimed;
    private Long userId;

    @BeforeEach
    void setUp() throws Exception {
        transaction = new TransactionTemplate(transactionManager);
        userId = transaction.execute(tx -> users.saveAndFlush(
            User.create("수신자", LocalDate.of(2000, 1, 1), Gender.FEMALE, CREATED_AT)).getId());
        request = new NotificationCreateRequested(1, "event:worker", userId, NotificationType.LIKE,
            "원본 제목", "원본 본문", "USER", 99L, REQUESTED_AT);
        transaction.executeWithoutResult(tx -> outboxService.append(request));
        claimed = claims.tryClaimNext().orElseThrow();
        topics.setDeliveryTopic("worker-delivery-" + UUID.randomUUID());
        try (
            var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topics.getDeliveryTopic(), 1, (short) 1)))
                .all().get(20, TimeUnit.SECONDS);
        }
    }

    @AfterEach
    void cleanUp() {
        Thread.interrupted();
        jdbc.update("DELETE FROM notification_request_outbox");
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void 저장_커밋과_Delivery_성공_이후에만_Outbox를_완료한다() throws Exception {
        var original = claimed.getPayload();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(notificationCount()).isEqualTo(1);
            assertThat(row()).containsEntry("status", "PENDING").containsEntry("completed_at", null);
            var metadata = invocation.callRealMethod();
            assertThat(row()).containsEntry("status", "PENDING");
            return metadata;
        }).when(producer).publishAndAwait(any());

        worker.process(claimed);

        assertThat(row()).containsEntry("status", "COMPLETED").containsEntry("payload", original)
            .containsEntry("processing_token", null).containsEntry("processing_until", null);
        assertThat(row().get("completed_at")).isNotNull();
        assertThat(notificationCount()).isEqualTo(1);
        var event = deliveries(1).getFirst();
        assertThat(event.sourceEventId()).isEqualTo(request.sourceEventId());
        assertThat(event.recipientUserId()).isEqualTo(userId);
        assertThat(event.notification().notificationId()).isEqualTo(notificationId());
        assertThat(event.notification().createdAt()).isEqualTo(CREATED_AT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"beforeDelivery", "afterDelivery", "completion"})
    void 저장_후_실패는_완료하지_않고_재점유_후_같은_알림으로_전달을_재시도한다(String stage) throws Exception {
        if (stage.equals("completion")) {
            jdbc.execute("""
                ALTER TABLE notification_request_outbox
                ADD CONSTRAINT test_worker_keep_pending CHECK (completed_at IS NULL)
                """);
        } else {
            doAnswer(invocation -> {
                if (stage.equals("afterDelivery")) {
                    invocation.callRealMethod();
                }
                throw new TimeoutException();
            }).when(producer).publishAndAwait(any());
        }
        var before = row();
        try {
            assertThatThrownBy(() -> worker.process(claimed))
                .isInstanceOf(stage.equals("completion") ? DataAccessException.class : TimeoutException.class);
        } finally {
            if (stage.equals("completion")) {
                jdbc.execute("ALTER TABLE notification_request_outbox DROP CHECK test_worker_keep_pending");
            }
        }
        assertThat(row()).isEqualTo(before);
        assertThat(notificationCount()).isEqualTo(1);
        Long originalId = notificationId();
        jdbc.update("UPDATE notifications SET read_at = ? WHERE id = ?", CREATED_AT.plusMinutes(1), originalId);
        var notification = jdbc.queryForMap("SELECT * FROM notifications WHERE id = ?", originalId);
        doCallRealMethod().when(producer).publishAndAwait(any());
        expire();
        var next = claims.tryClaimNext().orElseThrow();

        worker.process(next);

        assertThat(next.getProcessingToken()).isNotEqualTo(claimed.getProcessingToken());
        assertThat(row()).containsEntry("status", "COMPLETED").containsEntry("failure_count", 0);
        assertThat(notificationCount()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM notifications WHERE id = ?", originalId)).isEqualTo(notification);
        assertThat(deliveries(stage.equals("beforeDelivery") ? 1 : 2)).allSatisfy(event -> {
            assertThat(event.notification().notificationId()).isEqualTo(originalId);
            assertThat(event.notification().createdAt()).isEqualTo(CREATED_AT);
        });
    }

    @Test
    void 삭제된_기존_알림은_상태를_보존하고_Delivery_없이_Outbox를_완료한다() throws Exception {
        Long existing = creationService.createOrLoad(request).notification().notificationId();
        jdbc.update("UPDATE notifications SET deleted_at = ? WHERE id = ?", CREATED_AT.plusMinutes(1), existing);
        var before = jdbc.queryForMap("SELECT * FROM notifications WHERE id = ?", existing);

        worker.process(claimed);

        assertThat(row()).containsEntry("status", "COMPLETED");
        assertThat(jdbc.queryForMap("SELECT * FROM notifications WHERE id = ?", existing)).isEqualTo(before);
        verify(producer, never()).publishAndAwait(any());
        deliveries(0);
    }

    @Test
    void 복원할_수_없는_요청은_알림과_Delivery와_완료를_만들지_않는다() throws Exception {
        jdbc.update("UPDATE notification_request_outbox SET payload = '{}' WHERE id = ?", claimed.getId());
        expire();
        var invalid = claims.tryClaimNext().orElseThrow();
        var before = row();

        assertThatThrownBy(() -> worker.process(invalid)).isInstanceOf(NotificationInvalidCreateRequestException.class);

        assertThat(row()).isEqualTo(before);
        assertThat(notificationCount()).isZero();
        verify(producer, never()).publishAndAwait(any());
        deliveries(0);
    }

    @Test
    void 활성_트랜잭션에서는_Worker_실행을_거부한다() throws Exception {
        transaction.executeWithoutResult(tx -> assertThatThrownBy(() -> worker.process(claimed))
            .isInstanceOf(IllegalTransactionStateException.class));

        assertThat(row()).containsEntry("status", "PENDING");
        assertThat(notificationCount()).isZero();
        verify(producer, never()).publishAndAwait(any());
    }

    @Test
    void 처리_중_재점유되면_이전_실행은_완료하지_못하고_새_점유자가_기존_알림으로_처리한다() throws Exception {
        var next = new AtomicReference<NotificationRequestOutbox>();
        doAnswer(invocation -> {
            var metadata = invocation.callRealMethod();
            expire();
            next.set(claims.tryClaimNext().orElseThrow());
            return metadata;
        }).when(producer).publishAndAwait(any());

        assertThatThrownBy(() -> worker.process(claimed))
            .isInstanceOfSatisfying(NotificationBusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(NotificationErrorCode.OUTBOX_CLAIM_LOST));

        assertThat(row()).containsEntry("status", NotificationOutboxStatus.PENDING.name())
            .containsEntry("processing_token", next.get().getProcessingToken()).containsEntry("completed_at", null);
        doCallRealMethod().when(producer).publishAndAwait(any());
        worker.process(next.get());

        assertThat(row()).containsEntry("status", "COMPLETED");
        assertThat(notificationCount()).isEqualTo(1);
        assertThat(deliveries(2)).extracting(event -> event.notification().notificationId())
            .containsOnly(notificationId());
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT * FROM notification_request_outbox WHERE id = ?", claimed.getId());
    }

    private long notificationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Long.class);
    }

    private Long notificationId() {
        return jdbc.queryForObject("SELECT id FROM notifications WHERE source_event_id = ?", Long.class,
            request.sourceEventId());
    }

    private void expire() {
        jdbc.update("""
            UPDATE notification_request_outbox SET processing_until = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6))
            WHERE id = ?
            """, claimed.getId());
    }

    private List<NotificationDeliveryEvent> deliveries(int expected) {
        var partitions = List.of(new TopicPartition(topics.getDeliveryTopic(), 0));
        try (var consumer = new KafkaConsumer<Long, String>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "worker-test-" + UUID.randomUUID(),
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false), new LongDeserializer(), new StringDeserializer())) {
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            var events = new ArrayList<NotificationDeliveryEvent>();
            if (expected > 0) {
                var records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(20), expected);
                assertThat(records.count()).isEqualTo(expected);
                records.forEach(record -> {
                    assertThat(record.key()).isEqualTo(userId);
                    events.add(json.readValue(record.value(), NotificationDeliveryEvent.class));
                });
            }
            assertThat(consumer.endOffsets(partitions, Duration.ofSeconds(20)).values())
                .containsExactly((long) expected);
            return events;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({NotificationKafkaProperties.class, NotificationDeliveryProperties.class})
    static class PropertiesConfiguration {}
}
