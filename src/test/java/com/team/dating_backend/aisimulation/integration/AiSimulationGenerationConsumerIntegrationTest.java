package com.team.dating_backend.aisimulation.integration;

import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.TARGET_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.USER_ID;
import static com.team.dating_backend.aisimulation.AiSimulationTestFixtures.aiResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.team.dating_backend.aisimulation.client.AiSimulationAiClient;
import com.team.dating_backend.aisimulation.config.AiSimulationKafkaConfiguration;
import com.team.dating_backend.aisimulation.config.AiSimulationKafkaProperties;
import com.team.dating_backend.aisimulation.config.AiSimulationKafkaTopicConfiguration;
import com.team.dating_backend.aisimulation.config.AiSimulationRetryConfiguration;
import com.team.dating_backend.aisimulation.dto.event.AiSimulationCompletedEvent;
import com.team.dating_backend.aisimulation.dto.event.AiSimulationRequestedEvent;
import com.team.dating_backend.aisimulation.enums.AiSimulationSessionStatus;
import com.team.dating_backend.aisimulation.exception.AiSimulationRetryableProcessingException;
import com.team.dating_backend.aisimulation.kafka.AiSimulationGenerationConsumer;
import com.team.dating_backend.aisimulation.kafka.AiSimulationKafkaListeners;
import com.team.dating_backend.aisimulation.service.AiSimulationEventFactory;
import com.team.dating_backend.aisimulation.service.AiSimulationEventPublisher;
import com.team.dating_backend.aisimulation.service.AiSimulationGenerationService;
import com.team.dating_backend.aisimulation.service.AiSimulationPersistenceService;
import com.team.dating_backend.aisimulation.service.AiSimulationResponseValidator;
import com.team.dating_backend.common.config.SchedulingConfig;
import com.team.dating_backend.common.kafka.config.KafkaProducerConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest(
    showSql = false, properties = {
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.kafka.admin.auto-create=false",
        "app.ai-simulation.kafka.requested-topic=generation-test-requested",
        "app.ai-simulation.kafka.completed-topic=generation-test-completed",
        "app.ai-simulation.kafka.generation.group-id=generation-test",
        "app.ai-simulation.kafka.generation.enabled=true",
        "app.ai-simulation.kafka.partitions=4",
        "app.ai-simulation.kafka.concurrency=2",
        "app.ai-simulation.kafka.replication-factor=1",
        "app.ai-simulation.kafka.min-in-sync-replicas=1",
        "app.ai-simulation.kafka.retry.max-attempts=4",
        "app.ai-simulation.kafka.retry.concurrency=1",
        "app.ai-simulation.kafka.retry.delay-ms=50"
    }
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ImportAutoConfiguration(
    {KafkaAutoConfiguration.class, JacksonAutoConfiguration.class,
        TaskSchedulingAutoConfiguration.class}
)
@Import(
    {AiSimulationGenerationConsumer.class, AiSimulationGenerationService.class,
        AiSimulationPersistenceService.class, AiSimulationResponseValidator.class,
        AiSimulationEventPublisher.class, AiSimulationEventFactory.class,
        KafkaProducerConfiguration.class, AiSimulationKafkaConfiguration.class,
        AiSimulationRetryConfiguration.class, AiSimulationKafkaTopicConfiguration.class,
        SchedulingConfig.class, AiSimulationGenerationConsumerIntegrationTest.PropertiesConfiguration.class}
)
@EmbeddedKafka(
    partitions = 4, topics = {
        "generation-test-requested", "generation-test-completed",
        "generation-test-requested-generation-retry", "generation-test-requested-generation-dlt"
    }, brokerProperties = "auto.create.topics.enable=false"
)
@Testcontainers
@DirtiesContext
@Timeout(60)
class AiSimulationGenerationConsumerIntegrationTest {

    private static final String REQUESTED = "generation-test-requested";
    private static final String COMPLETED = "generation-test-completed";
    private static final String RETRY = REQUESTED + "-generation-retry";
    private static final String GROUP = "generation-test";

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private AiSimulationPersistenceService persistence;

    @Autowired
    private AiSimulationEventFactory events;

    @Autowired
    private KafkaTemplate<Object, Object> template;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private JsonMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private AiSimulationAiClient ai;

    @MockitoSpyBean
    private AiSimulationEventPublisher publisher;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM ai_simulation_messages");
        jdbc.update("DELETE FROM ai_simulation_reports");
        jdbc.update("DELETE FROM ai_simulation_sessions");
        when(ai.run(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return aiResponse();
        });
    }

    @Test
    void 요청을_처리하고_같은_사건을_다시_받아도_AI없이_동일한_완료_이벤트를_발행한다() throws Exception {
        var session = persistence.begin(USER_ID, TARGET_ID);
        var requested = events.requested(session.getId(), session.getCreatedAt());

        assertCommitted(GROUP, send(requested));
        var first = records(COMPLETED, session.getId(), 1).getFirst();
        assertThat(mapper.readValue(first.value(), AiSimulationCompletedEvent.class))
            .isEqualTo(
                events.completed(session.getId(), persistence.loadGenerationState(session.getId()).finishedAt()));

        assertCommitted(GROUP, send(requested));

        var completed = records(COMPLETED, session.getId(), 2);
        assertThat(completed).hasSize(2);
        assertThat(completed.getLast().value()).isEqualTo(first.value());
        assertThat(completed).allSatisfy(record -> assertThat(record.key()).isEqualTo(session.getId()));
        assertThat(messageCount(session.getId())).isEqualTo(20);
        verify(ai, times(1)).run(any());
    }

    @Test
    void AI_처리가_끝나기_전에는_원본_처리_위치를_확정하지_않는다() throws Exception {
        var session = persistence.begin(USER_ID, TARGET_ID);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(ai.run(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            started.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return aiResponse();
        });
        var sent = send(events.requested(session.getId(), session.getCreatedAt()));
        try {
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            try (var admin = admin()) {
                await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                    var offset = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS).get(new TopicPartition(REQUESTED, 3));
                    assertThat(offset == null || offset.offset() <= sent.offset()).isTrue();
                });
            }
            verify(publisher, never()).publishCompleted(any(), any());
        } finally {
            release.countDown();
        }
        assertCommitted(GROUP, sent);
        assertThat(records(COMPLETED, session.getId(), 1)).hasSize(1);
    }

    @Test
    void 재시도_대상_오류는_Retry로_전달하고_동일한_요청으로_생성을_완료한다() throws Exception {
        var session = persistence.begin(USER_ID, TARGET_ID);
        var requested = events.requested(session.getId(), session.getCreatedAt());
        var attempts = new AtomicInteger();
        when(ai.run(any())).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() < 3) {
                throw new AiSimulationRetryableProcessingException(new TimeoutException());
            }
            return aiResponse();
        });

        assertCommitted(GROUP, send(requested));
        assertThat(records(COMPLETED, session.getId(), 1)).hasSize(1);

        var retries = records(RETRY, session.getId(), 2);
        assertThat(retries).hasSize(2).allSatisfy(record -> {
            assertThat(record.partition()).isEqualTo(3);
            assertThat(mapper.readValue(record.value(), AiSimulationRequestedEvent.class)).isEqualTo(requested);
        });
        assertRetryCommitted(retries.getLast());
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(messageCount(session.getId())).isEqualTo(20);
    }

    @Test
    void 결과_커밋_후_완료_전달이_실패하면_Retry에서_AI없이_완료_전달을_복구한다() throws Exception {
        var session = persistence.begin(USER_ID, TARGET_ID);
        var attempts = new AtomicInteger();
        doAnswer(invocation -> {
            assertThat(persistence.loadGenerationState(session.getId()).status())
                .isEqualTo(AiSimulationSessionStatus.COMPLETED);
            if (attempts.incrementAndGet() == 1) {
                throw new AiSimulationRetryableProcessingException(new TimeoutException());
            }
            return invocation.callRealMethod();
        }).when(publisher).publishCompleted(eq(session.getId()), any());

        assertCommitted(GROUP, send(events.requested(session.getId(), session.getCreatedAt())));
        var completed = records(COMPLETED, session.getId(), 1).getFirst();

        assertRetryCommitted(records(RETRY, session.getId(), 1).getFirst());
        assertThat(mapper.readValue(completed.value(), AiSimulationCompletedEvent.class))
            .isEqualTo(
                events.completed(session.getId(), persistence.loadGenerationState(session.getId()).finishedAt()));
        assertThat(attempts.get()).isEqualTo(2);
        verify(ai, times(1)).run(any());
        assertThat(messageCount(session.getId())).isEqualTo(20);
    }

    @ParameterizedTest
    @EnumSource(value = AiSimulationSessionStatus.class, names = {"FAILED", "CANCELED"})
    void 종료된_세션의_요청은_생성과_완료_전달없이_처리를_끝낸다(AiSimulationSessionStatus status) throws Exception {
        var session = persistence.begin(USER_ID, TARGET_ID);
        jdbc.update("UPDATE ai_simulation_sessions SET status=?, finished_at=UTC_TIMESTAMP(6) WHERE id=?",
            status.name(), session.getId());

        assertCommitted(GROUP, send(events.requested(session.getId(), session.getCreatedAt())));

        verifyNoInteractions(ai);
        verify(publisher, never()).publishCompleted(any(), any());
        assertThat(messageCount(session.getId())).isZero();
        assertThat(persistence.loadGenerationState(session.getId()).status()).isEqualTo(status);
    }

    private RecordMetadata send(AiSimulationRequestedEvent event) throws Exception {
        return template.send(REQUESTED, 3, event.simulationId(), event).get(10, TimeUnit.SECONDS).getRecordMetadata();
    }

    private List<ConsumerRecord<Long, byte[]>> records(String topic, Long id, int expected) {
        try (var consumer = new KafkaConsumer<Long, byte[]>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false), new LongDeserializer(), new ByteArrayDeserializer())) {
            consumer.assign(IntStream.range(0, 4)
                .mapToObj(partition -> new TopicPartition(topic, partition)).toList());
            consumer.seekToBeginning(consumer.assignment());
            var result = new ArrayList<ConsumerRecord<Long, byte[]>>();
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (result.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(100)).forEach(record -> {
                    if (id.equals(record.key())) {
                        result.add(record);
                    }
                });
            }
            assertThat(result).hasSize(expected);
            return result;
        }
    }

    private void assertCommitted(String group, RecordMetadata record) {
        assertCommitted(group, new TopicPartition(record.topic(), record.partition()), record.offset() + 1);
    }

    private void assertRetryCommitted(ConsumerRecord<Long, byte[]> record) {
        assertCommitted(GROUP + "-generation-retry", new TopicPartition(record.topic(), record.partition()),
            record.offset() + 1);
        assertThat(registry.getListenerContainer(AiSimulationKafkaListeners.GENERATION + "-generation-dlt").isRunning())
            .isFalse();
    }

    private void assertCommitted(String group, TopicPartition partition, long expected) {
        try (var admin = admin()) {
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                var offset = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS).get(partition);
                assertThat(offset).isNotNull();
                assertThat(offset.offset()).isGreaterThanOrEqualTo(expected);
            });
        }
    }

    private Admin admin() {
        return Admin.create(Map.of("bootstrap.servers", broker.getBrokersAsString()));
    }

    private int messageCount(Long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ai_simulation_messages WHERE session_id=?", Integer.class, id);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiSimulationKafkaProperties.class)
    static class PropertiesConfiguration {}
}
