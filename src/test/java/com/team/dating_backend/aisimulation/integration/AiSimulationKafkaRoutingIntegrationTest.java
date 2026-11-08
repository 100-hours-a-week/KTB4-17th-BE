package com.team.dating_backend.aisimulation.integration;

import com.team.dating_backend.aisimulation.alert.AiSimulationFailureReporter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.team.dating_backend.aisimulation.config.AiSimulationKafkaConfiguration;
import com.team.dating_backend.aisimulation.config.AiSimulationKafkaProperties;
import com.team.dating_backend.aisimulation.config.AiSimulationKafkaTopicConfiguration;
import com.team.dating_backend.aisimulation.config.AiSimulationRetryConfiguration;
import com.team.dating_backend.aisimulation.dto.event.AiSimulationCompletedEvent;
import com.team.dating_backend.aisimulation.dto.event.AiSimulationRequestedEvent;
import com.team.dating_backend.aisimulation.exception.AiSimulationRetryableProcessingException;
import com.team.dating_backend.aisimulation.kafka.AiSimulationKafkaListeners;
import com.team.dating_backend.aisimulation.service.AiSimulationEventFactory;
import com.team.dating_backend.aisimulation.service.AiSimulationPersistenceService;
import com.team.dating_backend.common.config.SchedulingConfig;
import com.team.dating_backend.common.kafka.config.KafkaProducerConfiguration;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import tools.jackson.databind.json.JsonMapper;

class AiSimulationKafkaRoutingIntegrationTest {

    private static final String REQUESTED = "local-ai-simulation-requested";
    private static final String COMPLETED = "local-ai-simulation-completed";
    private static final String GENERATION_RETRY = REQUESTED + "-generation-retry";
    private static final String GENERATION_DLT = REQUESTED + "-generation-dlt";
    private static final String REQUESTER_DLT = COMPLETED + "-requester-notification-dlt";

    @Test
    @Timeout(120)
    void 실제_Kafka에서_실패한_업무만_재시도하고_DLT와_원본_소비_위치를_분리한다() {
        var topics = new ArrayList<>(List.of(REQUESTED, COMPLETED));
        for (String task : List.of("generation", "request-notification", "recommendation")) {
            topics.add(REQUESTED + "-" + task + "-retry");
            topics.add(REQUESTED + "-" + task + "-dlt");
        }
        for (String task : List.of("requester-notification", "target-notification")) {
            topics.add(COMPLETED + "-" + task + "-retry");
            topics.add(COMPLETED + "-" + task + "-dlt");
        }
        var broker = new EmbeddedKafkaKraftBroker(1, 4, topics.toArray(String[]::new));
        broker.brokerListProperty("ai-simulation.test.bootstrap-servers");
        broker.brokerProperties(Map.of("auto.create.topics.enable", "false"));
        try {
            broker.afterPropertiesSet();
            var listeners = new ProbeListeners();
            var runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, KafkaAutoConfiguration.class,
                    TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(SchedulingConfig.class, KafkaProducerConfiguration.class,
                    AiSimulationKafkaConfiguration.class, AiSimulationRetryConfiguration.class,
                    AiSimulationKafkaTopicConfiguration.class, PropertiesConfiguration.class)
                .withBean(ProbeListeners.class, () -> listeners)
                .withBean(AiSimulationPersistenceService.class, () -> mock(AiSimulationPersistenceService.class))
                .withBean(AiSimulationFailureReporter.class, () -> mock(AiSimulationFailureReporter.class))
                .withPropertyValues(
                    "spring.kafka.bootstrap-servers=" + broker.getBrokersAsString(),
                    "spring.kafka.admin.auto-create=false",
                    "spring.kafka.producer.acks=all",
                    "spring.kafka.producer.properties[spring.json.add.type.headers]=false",
                    "app.ai-simulation.kafka.replication-factor=1",
                    "app.ai-simulation.kafka.min-in-sync-replicas=1",
                    "app.ai-simulation.kafka.retry.delay-ms=50",
                    "app.ai-simulation.kafka.generation.enabled=true",
                    "app.ai-simulation.kafka.request-notification.enabled=true",
                    "app.ai-simulation.kafka.recommendation.enabled=true",
                    "app.ai-simulation.kafka.requester-notification.enabled=true",
                    "app.ai-simulation.kafka.target-notification.enabled=true");
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                var events = new AiSimulationEventFactory();
                var savedAt = LocalDateTime.of(2026, 11, 6, 10, 5);
                var requested = events.requested(10L, savedAt);
                var completed = events.completed(10L, savedAt.plusSeconds(50));
                var template = context.getBean(KafkaTemplate.class);
                template.send(REQUESTED, 3, 10L, requested).get(10, TimeUnit.SECONDS);
                template.send(COMPLETED, 3, 10L, completed).get(10, TimeUnit.SECONDS);
                assertThat(listeners.processed.await(40, TimeUnit.SECONDS)).isTrue();
                var records = forwardedRecords(broker.getBrokersAsString(), List.of(GENERATION_RETRY, REQUESTER_DLT),
                    3);
                var retries = records.stream().filter(record -> record.topic().equals(GENERATION_RETRY)).toList();
                var dlt = records.stream().filter(record -> record.topic().equals(REQUESTER_DLT)).toList();
                assertThat(retries).hasSize(2);
                assertThat(dlt).hasSize(1);
                var mapper = context.getBean(JsonMapper.class);
                for (var record : retries) {
                    assertThat(record.partition()).isEqualTo(3);
                    assertThat(record.key()).isEqualTo(10L);
                    assertThat(mapper.readValue(record.value(), AiSimulationRequestedEvent.class)).isEqualTo(requested);
                }
                assertThat(dlt.getFirst().partition()).isEqualTo(3);
                assertThat(dlt.getFirst().key()).isEqualTo(10L);
                assertThat(mapper.readValue(dlt.getFirst().value(), AiSimulationCompletedEvent.class))
                    .isEqualTo(completed);
                assertThat(listeners.generation.get()).isEqualTo(3);
                assertThat(listeners.requestNotification.get()).isEqualTo(1);
                assertThat(listeners.recommendation.get()).isEqualTo(1);
                assertThat(listeners.requester.get()).isEqualTo(1);
                assertThat(listeners.target.get()).isEqualTo(1);
                var registry = context.getBean(KafkaListenerEndpointRegistry.class);
                assertThat(registry.getListenerContainer(
                    AiSimulationKafkaListeners.REQUESTER_NOTIFICATION + "-requester-notification-dlt").isRunning())
                    .isFalse();
                assertCommitted(broker.getBrokersAsString(), "local-ai-simulation-generation", REQUESTED, 1L);
                assertCommitted(broker.getBrokersAsString(), "local-ai-simulation-generation-generation-retry",
                    GENERATION_RETRY, 2L);
                assertCommitted(broker.getBrokersAsString(), "local-ai-simulation-requester-notification", COMPLETED,
                    1L);
                listeners.exhaust = true;
                var exhausted = events.requested(20L, savedAt.plusMinutes(1));
                template.send(REQUESTED, 3, 20L, exhausted).get(10, TimeUnit.SECONDS);
                var exhaustedRecords = forwardedRecords(broker.getBrokersAsString(), List.of(GENERATION_DLT), 1);
                assertThat(exhaustedRecords).hasSize(1);
                var exhaustedRecord = exhaustedRecords.getFirst();
                assertThat(exhaustedRecord.partition()).isEqualTo(3);
                assertThat(exhaustedRecord.key()).isEqualTo(20L);
                assertThat(mapper.readValue(exhaustedRecord.value(), AiSimulationRequestedEvent.class))
                    .isEqualTo(exhausted);
                assertThat(listeners.generation.get()).isEqualTo(7);
                assertThat(registry.getListenerContainer(AiSimulationKafkaListeners.GENERATION + "-generation-dlt")
                    .isRunning()).isFalse();
                assertCommitted(broker.getBrokersAsString(), "local-ai-simulation-generation", REQUESTED, 2L);
                assertCommitted(broker.getBrokersAsString(), "local-ai-simulation-generation-generation-retry",
                    GENERATION_RETRY, 5L);
            });
        } finally {
            broker.destroy();
        }
    }

    private List<ConsumerRecord<Long, byte[]>> forwardedRecords(String bootstrap, List<String> topics, int expected) {
        try (var consumer = new KafkaConsumer<Long, byte[]>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false), new LongDeserializer(), new ByteArrayDeserializer())) {
            consumer.assign(topics.stream().map(topic -> new TopicPartition(topic, 3)).toList());
            consumer.seekToBeginning(consumer.assignment());
            var records = new ArrayList<ConsumerRecord<Long, byte[]>>();
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (records.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            return records;
        }
    }

    private void assertCommitted(String bootstrap, String group, String topic, long expected) throws Exception {
        try (var admin = Admin.create(Map.of("bootstrap.servers", bootstrap))) {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            do {
                var offset = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                    .get(3, TimeUnit.SECONDS).get(new TopicPartition(topic, 3));
                if (offset != null && offset.offset() == expected) {
                    return;
                }
                Thread.sleep(50);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("Consumer offset was not committed: " + group);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiSimulationKafkaProperties.class)
    static class PropertiesConfiguration {}

    static class ProbeListeners {

        final AtomicInteger generation = new AtomicInteger();
        final AtomicInteger requestNotification = new AtomicInteger();
        final AtomicInteger recommendation = new AtomicInteger();
        final AtomicInteger requester = new AtomicInteger();
        final AtomicInteger target = new AtomicInteger();
        final CountDownLatch processed = new CountDownLatch(5);
        volatile boolean exhaust;

        @AiSimulationKafkaListeners.Generation
        void generate(AiSimulationRequestedEvent event) {
            if (generation.incrementAndGet() < 3 || exhaust) {
                throw new AiSimulationRetryableProcessingException(new IllegalStateException());
            }
            processed.countDown();
        }

        @AiSimulationKafkaListeners.RequestNotification
        void requestNotification(AiSimulationRequestedEvent event) {
            requestNotification.incrementAndGet();
            processed.countDown();
        }

        @AiSimulationKafkaListeners.Recommendation
        void recommend(AiSimulationRequestedEvent event) {
            recommendation.incrementAndGet();
            processed.countDown();
        }

        @AiSimulationKafkaListeners.RequesterNotification
        void requesterNotification(AiSimulationCompletedEvent event) {
            requester.incrementAndGet();
            processed.countDown();
            throw new IllegalArgumentException();
        }

        @AiSimulationKafkaListeners.TargetNotification
        void targetNotification(AiSimulationCompletedEvent event) {
            target.incrementAndGet();
            processed.countDown();
        }
    }
}
