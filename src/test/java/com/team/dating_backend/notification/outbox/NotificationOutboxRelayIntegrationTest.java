package com.team.dating_backend.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.notification.config.NotificationKafkaConfiguration;
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
    @MockitoSpyBean
    private NotificationRequestProducer producer;
    @MockitoSpyBean(name = "kafkaTemplate")
    private KafkaTemplate<?, ?> kafkaTemplate;

    private TransactionTemplate transaction;
    private Long userId;
    private String topic;

    @BeforeEach
    void setUp() throws Exception {
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
            assertThat(row(id)).isEqualTo(before);
            Object result = invocation.callRealMethod();
            assertThat(row(id)).isEqualTo(before);
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

        assertThatThrownBy(() -> relay.publish(id)).isInstanceOf(TimeoutException.class);

        assertThat(row(id)).isEqualTo(before);
        var success = actual.get().get(20, TimeUnit.SECONDS);
        assertThat(delayed.complete(success)).isTrue();
        assertThat(row(id)).isEqualTo(before);
        verify(producer).publishAndAwait(request);
        assertRecords(request, 1);

        doCallRealMethod().when(template()).send(eq(topic), eq(userId), eq(request));
        publishProperties.setResultTimeoutMs(15_000L);
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
            assertThatThrownBy(() -> relay.publish(id)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("test_reject_outbox_published");

            assertThat(row(id)).isEqualTo(before);
            verify(producer).publishAndAwait(request);
            assertRecords(request, 1);
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
    void DB에_남은_유효하지_않은_payload는_발행하지_않고_보존한다() {
        Long id = save(request());
        jdbc.update("UPDATE notification_request_outbox SET payload = '{}' WHERE id = ?", id);
        var before = row(id);

        assertThatThrownBy(() -> relay.publish(id)).isInstanceOf(NotificationInvalidCreateRequestException.class);

        assertThat(row(id)).isEqualTo(before);
        verifyNoInteractions(producer);
    }

    private Long save(NotificationCreateRequested request) {
        return transaction.execute(tx -> outboxService.append(request).getId());
    }

    private NotificationCreateRequested request() {
        return new NotificationCreateRequested(1, "LIKE_SENT:" + UUID.randomUUID(), userId, NotificationType.LIKE,
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
            for (var record : records) {
                assertThat(record.topic()).isEqualTo(topic);
                assertThat(record.key()).isEqualTo(userId);
                assertThat(record.headers().lastHeader("__TypeId__")).isNull();
                assertThat(jsonMapper.readValue(record.value(), NotificationCreateRequested.class)).isEqualTo(request);
            }
            assertThat(consumer.endOffsets(partitions, Duration.ofSeconds(20)).values().stream()
                .mapToLong(Long::longValue).sum()).isEqualTo(expectedCount);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({NotificationKafkaProperties.class, NotificationPublishProperties.class})
    static class KafkaConfiguration {

        @Bean
        DefaultKafkaProducerFactoryCustomizer notificationKafkaProducerFactoryCustomizer(JsonMapper mapper) {
            return new NotificationKafkaConfiguration().notificationKafkaProducerFactoryCustomizer(mapper);
        }
    }
}
