package com.team.dating_backend.notification.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.team.dating_backend.notification.config.NotificationCreationProperties;
import com.team.dating_backend.notification.config.NotificationKafkaConfiguration;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.service.NotificationCreateRequestedValidator;
import com.team.dating_backend.notification.service.NotificationCreationProcessor;
import com.team.dating_backend.notification.service.NotificationCreationService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class NotificationCreationConsumerIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @Test
    void Processor가_완료되기_전에는_commit하지_않고_정상_반환_후_commit한다() throws Exception {
        String topic = "creation-wait-" + UUID.randomUUID();
        String group = "creation-group-" + UUID.randomUUID();
        var processor = mock(NotificationCreationProcessor.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(20, TimeUnit.SECONDS)) {
                throw new TimeoutException();
            }
            return null;
        }).when(processor).process(any());
        try (Admin admin = admin(topic, 1)) {
            contextRunner(topic, group, processor)
                .withPropertyValues("APP_NOTIFICATION_CREATION_ENABLED=true").run(context -> {
                    var request = request("waiting", 1);
                    send(context, topic, 0, request);
                    var container = start(context, 1);
                    try {
                        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                        assertThat(committed(admin, group, topic, 0)).isNull();
                        assertThat(container.isRunning()).isTrue();
                        release.countDown();
                        await().atMost(Duration.ofSeconds(15))
                            .untilAsserted(() -> assertThat(committed(admin, group, topic, 0)).isEqualTo(1L));
                        verify(processor).process(request);
                        verifyNoMoreInteractions(processor);
                    } finally {
                        release.countDown();
                    }
                });
        }
    }

    @ParameterizedTest
    @MethodSource("processingFailures")
    void 처리_실패시_중단하고_성공한_이전_offset만_commit한다(Exception failure) throws Exception {
        String topic = "creation-failure-" + UUID.randomUUID();
        String group = "creation-group-" + UUID.randomUUID();
        var processor = mock(NotificationCreationProcessor.class);
        doAnswer(invocation -> {
            NotificationCreateRequested request = invocation.getArgument(0);
            if (request.sourceEventId().equals("failed")) {
                throw failure;
            }
            return null;
        }).when(processor).process(any());
        try (Admin admin = admin(topic, 1)) {
            contextRunner(topic, group, processor)
                .withPropertyValues("APP_NOTIFICATION_CREATION_MAX_POLL_RECORDS=10")
                .run(context -> {
                    var first = request("first", 1);
                    var failed = request("failed", 1);
                    send(context, topic, 0, first);
                    send(context, topic, 0, failed);
                    send(context, topic, 0, request("after-failure", 1));
                    var container = listener(context);
                    container.start();
                    awaitStopped(container);
                    assertThat(committed(admin, group, topic, 0)).isEqualTo(1L);
                    verify(processor).process(first);
                    verify(processor).process(failed);
                    verifyNoMoreInteractions(processor);
                });
        }
    }

    @ParameterizedTest
    @EnumSource(InvalidPayload.class)
    void 잘못된_입력은_저장과_발행_없이_중단하고_offset을_넘기지_않는다(InvalidPayload input) throws Exception {
        String topic = "creation-invalid-" + UUID.randomUUID();
        String group = "creation-group-" + UUID.randomUUID();
        var creationService = mock(NotificationCreationService.class);
        var deliveryProducer = mock(NotificationDeliveryProducer.class);
        var processor = new NotificationCreationProcessor(
            new NotificationCreateRequestedValidator(), creationService, deliveryProducer);
        try (Admin admin = admin(topic, 1)) {
            contextRunner(topic, group, processor).run(context -> {
                String payload = switch (input) {
                    case MALFORMED_JSON -> "{invalid";
                    case JSON_NULL -> "null";
                    case TOMBSTONE -> null;
                    case UNSUPPORTED_SCHEMA ->
                        context.getBean(JsonMapper.class).writeValueAsString(request("invalid", 2));
                };
                try (var producer = new KafkaProducer<String, String>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
                    producer.send(new ProducerRecord<>(topic, 0, "7", payload)).get(10, TimeUnit.SECONDS);
                }
                send(context, topic, 0, request("after-invalid", 1));
                var container = listener(context);
                container.start();
                awaitStopped(container);
                assertThat(committed(admin, group, topic, 0)).isNull();
                verifyNoInteractions(creationService, deliveryProducer);
            });
        }
    }

    @Test
    void 동일_Group의_두_인스턴스는_파티션을_나누어_각_레코드를_한번씩_처리한다() throws Exception {
        String topic = "creation-shared-" + UUID.randomUUID();
        String group = "creation-group-" + UUID.randomUUID();
        var firstProcessor = mock(NotificationCreationProcessor.class);
        var secondProcessor = mock(NotificationCreationProcessor.class);
        var firstReceived = new ConcurrentLinkedQueue<String>();
        var secondReceived = new ConcurrentLinkedQueue<String>();
        doAnswer(invocation -> {
            firstReceived.add(((NotificationCreateRequested) invocation.getArgument(0)).sourceEventId());
            return null;
        }).when(firstProcessor).process(any());
        doAnswer(invocation -> {
            secondReceived.add(((NotificationCreateRequested) invocation.getArgument(0)).sourceEventId());
            return null;
        }).when(secondProcessor).process(any());
        try (Admin admin = admin(topic, 2)) {
            contextRunner(topic, group, firstProcessor)
                .run(first -> contextRunner(topic, group, secondProcessor).run(second -> {
                    var firstContainer = listener(first);
                    var secondContainer = listener(second);
                    firstContainer.start();
                    secondContainer.start();
                    await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                        assertThat(firstContainer.getAssignedPartitions()).hasSize(1);
                        assertThat(secondContainer.getAssignedPartitions()).hasSize(1)
                            .doesNotContainAnyElementsOf(firstContainer.getAssignedPartitions());
                    });
                    send(first, topic, 0, request("partition-0", 1));
                    send(first, topic, 1, request("partition-1", 1));
                    await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                        assertThat(committed(admin, group, topic, 0)).isEqualTo(1L);
                        assertThat(committed(admin, group, topic, 1)).isEqualTo(1L);
                    });
                    assertThat(firstReceived).hasSize(1);
                    assertThat(secondReceived).hasSize(1);
                    assertThat(Stream.concat(firstReceived.stream(), secondReceived.stream()).toList())
                        .containsExactlyInAnyOrder("partition-0", "partition-1");
                }));
        }
    }

    private ApplicationContextRunner contextRunner(String topic, String group,
        NotificationCreationProcessor processor) {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, KafkaAutoConfiguration.class))
            .withUserConfiguration(NotificationKafkaConfiguration.class, NotificationCreationConsumer.class,
                PropertiesConfiguration.class)
            .withBean(NotificationCreationProcessor.class, () -> processor)
            .withBean(KafkaConnectionDetails.class, () -> () -> List.of(KAFKA.getBootstrapServers()))
            .withPropertyValues("NOTIFICATION_CREATE_REQUESTED_TOPIC=" + topic,
                "APP_NOTIFICATION_CREATION_GROUP_ID=" + group)
            .withInitializer(context -> {
                var sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                try {
                    new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
                        .forEach(sources::addLast);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
    }

    private Admin admin(String topic, int partitions) throws Exception {
        Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
        try {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get(15, TimeUnit.SECONDS);
            return admin;
        } catch (Exception exception) {
            admin.close();
            throw exception;
        }
    }

    private ConcurrentMessageListenerContainer<?, ?> listener(AssertableApplicationContext context) {
        assertThat(context).hasNotFailed();
        return (ConcurrentMessageListenerContainer<?, ?>) context.getBean(KafkaListenerEndpointRegistry.class)
            .getListenerContainer("notificationCreationConsumer");
    }

    private ConcurrentMessageListenerContainer<?, ?> start(AssertableApplicationContext context, int partitions) {
        var container = listener(context);
        container.start();
        await().atMost(Duration.ofSeconds(20))
            .untilAsserted(() -> assertThat(container.getAssignedPartitions()).hasSize(partitions));
        return container;
    }

    private void awaitStopped(ConcurrentMessageListenerContainer<?, ?> container) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(container.isRunning()).isFalse();
            assertThat(container.isInExpectedState()).isFalse();
        });
    }

    private Long committed(Admin admin, String group, String topic, int partition) throws Exception {
        OffsetAndMetadata offset = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
            .get(10, TimeUnit.SECONDS).get(new TopicPartition(topic, partition));
        return offset == null ? null : offset.offset();
    }

    private void send(AssertableApplicationContext context, String topic, int partition,
        NotificationCreateRequested request) throws Exception {
        context.getBean(KafkaTemplate.class).send(topic, partition, "7", request).get(10, TimeUnit.SECONDS);
    }

    private static NotificationCreateRequested request(String sourceEventId, int schemaVersion) {
        return new NotificationCreateRequested(schemaVersion, sourceEventId, 7L, NotificationType.LIKE,
            "제목", "본문", null, null, Instant.parse("2026-10-22T01:00:00Z"));
    }

    private static Stream<Exception> processingFailures() {
        return Stream.of(new DataAccessResourceFailureException("storage failure"),
            new ExecutionException(new IllegalStateException()), new TimeoutException());
    }

    private enum InvalidPayload {
        MALFORMED_JSON, JSON_NULL, TOMBSTONE, UNSUPPORTED_SCHEMA
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationCreationProperties.class)
    static class PropertiesConfiguration {}
}
