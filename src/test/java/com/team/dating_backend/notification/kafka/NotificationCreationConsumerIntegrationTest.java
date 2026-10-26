package com.team.dating_backend.notification.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import com.team.dating_backend.notification.alert.DiscordWebhookClient;
import com.team.dating_backend.notification.alert.NotificationFailureContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.team.dating_backend.notification.config.NotificationCreationProperties;
import com.team.dating_backend.notification.config.NotificationKafkaConfiguration;
import com.team.dating_backend.notification.config.NotificationKafkaProperties;
import com.team.dating_backend.notification.config.NotificationRetryConfiguration;
import com.team.dating_backend.notification.config.NotificationRetryProperties;
import com.team.dating_backend.common.config.SchedulingConfig;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.service.NotificationCreateRequestedValidator;
import com.team.dating_backend.notification.service.NotificationCreationProcessor;
import com.team.dating_backend.notification.service.NotificationCreationService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.retrytopic.RetryTopicHeaders;
import tools.jackson.databind.json.JsonMapper;
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
    void 영구_오류는_DLT로_인계한_뒤_후속_요청을_처리한다(Exception failure) throws Exception {
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
                    var afterFailure = request("after-failure", 1);
                    send(context, topic, 0, first);
                    send(context, topic, 0, failed);
                    send(context, topic, 0, afterFailure);
                    var container = start(context, 1);
                    await().atMost(Duration.ofSeconds(15))
                        .untilAsserted(() -> assertThat(committed(admin, group, topic, 0)).isEqualTo(3L));
                    assertThat(container.isRunning()).isTrue();
                    verify(processor).process(first);
                    verify(processor).process(failed);
                    verify(processor).process(afterFailure);
                    verifyNoMoreInteractions(processor);
                    var deadLetter = readRecord(topic + "-dlt", 0);
                    assertThat(deadLetter.key()).isEqualTo("7");
                    assertThat(context.getBean(JsonMapper.class)
                        .readValue(deadLetter.value(), NotificationCreateRequested.class)).isEqualTo(failed);
                });
        }
    }

    @Test
    void 일시_오류는_Retry로_인계한_뒤_원본_Group이_후속_요청을_계속_처리한다() throws Exception {
        String topic = "creation-retry-" + UUID.randomUUID();
        String group = "creation-group-" + UUID.randomUUID();
        var processor = mock(NotificationCreationProcessor.class);
        var transientAttempts = new AtomicInteger();
        var processed = new ConcurrentLinkedQueue<String>();
        var retryEntered = new CountDownLatch(1);
        var releaseRetry = new CountDownLatch(1);
        var retryCompleted = new CountDownLatch(1);
        doAnswer(invocation -> {
            var request = (NotificationCreateRequested) invocation.getArgument(0);
            processed.add(request.sourceEventId());
            if (request.sourceEventId().equals("transient")) {
                int attempt = transientAttempts.getAndIncrement();
                if (attempt == 0) {
                    throw new TimeoutException();
                }
                if (attempt == 1) {
                    retryEntered.countDown();
                    if (!releaseRetry.await(20, TimeUnit.SECONDS)) {
                        throw new TimeoutException();
                    }
                    retryCompleted.countDown();
                }
            }
            return null;
        }).when(processor).process(any());
        try (Admin admin = admin(topic, 1)) {
            contextRunner(topic, group, processor).withPropertyValues("APP_NOTIFICATION_CREATION_ENABLED=true")
                .run(context -> {
                    send(context, topic, 0, request("transient", 1));
                    send(context, topic, 0, request("after-retry", 1));
                    var container = start(context, 1);
                    try {
                        assertThat(retryEntered.await(15, TimeUnit.SECONDS)).isTrue();
                        await().atMost(Duration.ofSeconds(15))
                            .untilAsserted(() -> assertThat(committed(admin, group, topic, 0)).isEqualTo(2L));
                        assertThat(processed).contains("after-retry");
                        assertThat(container.isRunning()).isTrue();
                        var retryRecord = readRecord(topic + "-retry", 0);
                        assertThat(retryRecord.key()).isEqualTo("7");
                        assertThat(retryRecord.headers().lastHeader("__TypeId__")).isNull();
                        assertThat(context.getBean(JsonMapper.class)
                            .readValue(retryRecord.value(), NotificationCreateRequested.class))
                            .isEqualTo(request("transient", 1));
                    } finally {
                        releaseRetry.countDown();
                    }
                    assertThat(retryCompleted.await(15, TimeUnit.SECONDS)).isTrue();
                    await().atMost(Duration.ofSeconds(15))
                        .untilAsserted(() -> assertThat(transientAttempts.get()).isEqualTo(2));
                    assertThat(processed).contains("after-retry");
                    verify(processor, times(2)).process(request("transient", 1));
                    verify(processor).process(request("after-retry", 1));
                    verifyNoMoreInteractions(processor);
                });
        }
    }

    @Test
    void 고정_5초_간격으로_같은_Topic에서_3회_재시도한_뒤_DLT로_인계한다() throws Exception {
        String topic = "creation-exhausted-" + UUID.randomUUID();
        String group = "creation-group-" + UUID.randomUUID();
        var processor = mock(NotificationCreationProcessor.class);
        var startedAt = new CopyOnWriteArrayList<Long>();
        doAnswer(invocation -> {
            startedAt.add(System.currentTimeMillis());
            throw new TimeoutException();
        }).when(processor).process(any());
        try (Admin admin = admin(topic, 1)) {
            contextRunner(topic, group, processor).withPropertyValues(
                "APP_NOTIFICATION_CREATION_ENABLED=true", "APP_NOTIFICATION_RETRY_DELAY_MS=5000")
                .run(context -> {
                    var client = context.getBean(DiscordWebhookClient.class);
                    doAnswer(invocation -> {
                        NotificationFailureContext failure = invocation.getArgument(0);
                        assertThat(failure.errorCode().name()).isEqualTo("DLT_RECORDED");
                        assertThat(failure.kafkaDetails().attempt()).isEqualTo(4);
                        assertThat(failure.kafkaDetails().consumerGroup()).isEqualTo(group + "-retry");
                        assertThat(failure.kafkaDetails().inputLocation().topic()).isEqualTo(topic + "-retry");
                        assertThat(failure.kafkaDetails().inputLocation().offset()).isEqualTo(2L);
                        assertThat(failure.kafkaDetails().originalLocation().topic()).isEqualTo(topic);
                        assertThat(failure.kafkaDetails().destinationLocation().offset()).isZero();
                        assertThat(endOffset(admin, topic + "-dlt")).isEqualTo(1);
                        assertThat(committed(admin, group + "-retry", topic + "-retry", 0)).isEqualTo(2L);
                        return null;
                    }).when(client).send(any());
                    var request = request("exhausted", 1);
                    send(context, topic, 0, request);
                    start(context, 1);
                    await().atMost(Duration.ofSeconds(20))
                        .untilAsserted(() -> assertThat(committed(admin, group, topic, 0)).isEqualTo(1L));
                    await().atMost(Duration.ofSeconds(45))
                        .untilAsserted(() -> assertThat(endOffset(admin, topic + "-dlt")).isEqualTo(1L));
                    var deadLetter = readRecord(topic + "-dlt", 0);
                    assertThat(deadLetter.key()).isEqualTo("7");
                    assertThat(context.getBean(JsonMapper.class)
                        .readValue(deadLetter.value(), NotificationCreateRequested.class)).isEqualTo(request);
                    await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                        verify(client).send(any());
                        assertThat(committed(admin, group + "-retry", topic + "-retry", 0)).isEqualTo(3L);
                    });
                    assertThat(startedAt).hasSize(4);
                    assertThat(endOffset(admin, topic + "-retry")).isEqualTo(3L);
                    var retryRecords = readRecords(topic + "-retry", 0, 3);
                    for (int index = 0; index < retryRecords.size(); index++) {
                        var retry = retryRecords.get(index);
                        assertThat(retry.offset()).isEqualTo(index);
                        assertThat(retry.key()).isEqualTo("7");
                        assertThat(context.getBean(JsonMapper.class)
                            .readValue(retry.value(), NotificationCreateRequested.class)).isEqualTo(request);
                        assertThat(ByteBuffer.wrap(retry.headers().lastHeader(RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS)
                            .value()).getInt()).isEqualTo(index + 2);
                        long dueAt = new BigInteger(retry.headers()
                            .lastHeader(RetryTopicHeaders.DEFAULT_HEADER_BACKOFF_TIMESTAMP).value()).longValueExact();
                        assertThat(dueAt).isGreaterThanOrEqualTo(startedAt.get(index) + 5_000L);
                        assertThat(startedAt.get(index + 1)).isGreaterThanOrEqualTo(dueAt);
                    }
                });
        }
    }

    @ParameterizedTest
    @EnumSource(InvalidPayload.class)
    void 잘못된_입력은_DLT에_보관하고_malformed_byte를_보존한다(InvalidPayload input) throws Exception {
        String topic = "creation-invalid-" + UUID.randomUUID();
        String group = "creation-group-" + UUID.randomUUID();
        var creationService = mock(NotificationCreationService.class);
        var deliveryProducer = mock(NotificationDeliveryProducer.class);
        var processor = new NotificationCreationProcessor(
            new NotificationCreateRequestedValidator(), creationService, deliveryProducer);
        try (Admin admin = admin(topic, 1)) {
            contextRunner(topic, group, processor).run(context -> {
                byte[] payload = switch (input) {
                    case MALFORMED_JSON -> "{invalid".getBytes(StandardCharsets.UTF_8);
                    case JSON_NULL -> "null".getBytes(StandardCharsets.UTF_8);
                    case TOMBSTONE -> null;
                    case UNSUPPORTED_SCHEMA ->
                        context.getBean(JsonMapper.class).writeValueAsBytes(request("invalid", 2));
                };
                try (var producer = new KafkaProducer<String, byte[]>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
                    producer.send(new ProducerRecord<>(topic, 0, "7", payload)).get(10, TimeUnit.SECONDS);
                }
                var container = start(context, 1);
                await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> assertThat(committed(admin, group, topic, 0)).isEqualTo(1L));
                assertThat(container.isRunning()).isTrue();
                var deadLetter = readRecord(topic + "-dlt", 0);
                assertThat(deadLetter.key()).isEqualTo("7");
                assertThat(new String(deadLetter.headers().lastHeader(KafkaHeaders.ORIGINAL_TOPIC).value(),
                    StandardCharsets.UTF_8)).isEqualTo(topic);
                assertThat(ByteBuffer.wrap(deadLetter.headers().lastHeader(KafkaHeaders.ORIGINAL_PARTITION)
                    .value()).getInt()).isZero();
                assertThat(ByteBuffer.wrap(deadLetter.headers().lastHeader(KafkaHeaders.ORIGINAL_OFFSET)
                    .value()).getLong()).isZero();
                switch (input) {
                    case MALFORMED_JSON -> assertThat(deadLetter.value()).isEqualTo(payload);
                    case JSON_NULL, TOMBSTONE -> assertThat(deadLetter.value()).isNull();
                    case UNSUPPORTED_SCHEMA -> assertThat(context.getBean(JsonMapper.class)
                        .readValue(deadLetter.value(), NotificationCreateRequested.class))
                        .isEqualTo(request("invalid", 2));
                }
                var failure = ArgumentCaptor.forClass(NotificationFailureContext.class);
                verify(context.getBean(DiscordWebhookClient.class)).send(failure.capture());
                assertThat(failure.getValue().errorCode().name()).isEqualTo("DLT_RECORDED");
                assertThat(failure.getValue().kafkaDetails().destinationLocation().offset())
                    .isEqualTo(deadLetter.offset());
                assertThat(failure.getValue().kafkaDetails().attempt()).isEqualTo(1);
                if (input != InvalidPayload.UNSUPPORTED_SCHEMA) {
                    assertThat(failure.getValue().sourceEventId()).isNull();
                }
                verifyNoInteractions(creationService, deliveryProducer);
            });
        }
    }

    @ParameterizedTest
    @MethodSource("stoppingFailures")
    void 중단_예외와_Error는_입력_위치를_진행하지_않고_컨테이너를_중지한다(Throwable failure) throws Exception {
        String topic = "creation-stop-" + UUID.randomUUID();
        String group = "creation-group-" + UUID.randomUUID();
        var processor = mock(NotificationCreationProcessor.class);
        doAnswer(invocation -> {
            throw failure;
        }).when(processor).process(any());
        try (Admin admin = admin(topic, 1)) {
            contextRunner(topic, group, processor).withPropertyValues("APP_NOTIFICATION_CREATION_ENABLED=true")
                .run(context -> {
                    var container = start(context, 1);
                    send(context, topic, 0, request("stop", 1));
                    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                        assertThat(container.isRunning()).isFalse();
                        assertThat(container.isInExpectedState()).isFalse();
                    });
                    assertThat(committed(admin, group, topic, 0)).isNull();
                    assertThat(endOffset(admin, topic + "-retry")).isZero();
                    assertThat(endOffset(admin, topic + "-dlt")).isZero();
                });
        }
    }

    @ParameterizedTest
    @EnumSource(HandoffStage.class)
    void 인계_발행이_실패하면_입력_Offset을_유지하고_같은_요청을_재처리한다(HandoffStage stage) throws Exception {
        String topic = "creation-handoff-" + UUID.randomUUID();
        String group = "creation-group-" + UUID.randomUUID();
        var processor = mock(NotificationCreationProcessor.class);
        var processingAttempts = new AtomicInteger();
        doAnswer(invocation -> {
            processingAttempts.incrementAndGet();
            if (stage == HandoffStage.MAIN_DLT) {
                throw new IllegalStateException();
            }
            throw new TimeoutException();
        }).when(processor).process(any());
        try (Admin admin = admin(topic, 1)) {
            contextRunner(topic, group, processor)
                .withUserConfiguration(SpyTemplateConfiguration.class)
                .withPropertyValues("APP_NOTIFICATION_CREATION_ENABLED=true")
                .run(context -> {
                    var template = context.getBean(KafkaTemplate.class);
                    var reject = new AtomicBoolean(true);
                    var rejected = new CountDownLatch(1);
                    String destination = topic + stage.destinationSuffix;
                    doAnswer(invocation -> {
                        ProducerRecord<?, ?> record = invocation.getArgument(0);
                        if (record.topic().equals(destination) && reject.get()
                            && ByteBuffer.wrap(record.headers().lastHeader(RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS)
                                .value()).getInt() == stage.attempt + 1) {
                            rejected.countDown();
                            return CompletableFuture.failedFuture(new TimeoutException());
                        }
                        return invocation.callRealMethod();
                    }).when(template).send(any(ProducerRecord.class));
                    var request = request("handoff", 1);
                    send(context, topic, 0, request);
                    start(context, 1);
                    String inputTopic = topic + stage.inputSuffix;
                    String inputGroup = groupFor(context, inputTopic);
                    try {
                        assertThat(rejected.await(15, TimeUnit.SECONDS)).isTrue();
                        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                            if (stage.inputOffset == 0) {
                                assertThat(committed(admin, inputGroup, inputTopic, 0)).isNull();
                            } else {
                                assertThat(committed(admin, inputGroup, inputTopic, 0)).isEqualTo(stage.inputOffset);
                            }
                            assertThat(endOffset(admin, destination))
                                .isEqualTo(
                                    stage.inputSuffix.equals(stage.destinationSuffix) ? stage.inputOffset + 1 : 0L);
                            String errorCode = destination.endsWith("-dlt")
                                ? "DLT_PUBLISH_FAILED"
                                : "RETRY_PUBLISH_FAILED";
                            assertThat(context.getBean(MeterRegistry.class).get("notification.failures")
                                .tag("error_code", errorCode).counter().count()).isGreaterThanOrEqualTo(1);
                            verifyNoInteractions(context.getBean(DiscordWebhookClient.class));
                        });
                    } finally {
                        reject.set(false);
                    }
                    await().atMost(Duration.ofSeconds(20))
                        .untilAsserted(() -> assertThat(committed(admin, inputGroup, inputTopic, 0))
                            .isGreaterThanOrEqualTo(stage.inputOffset + 1));
                    var deadLetter = readRecord(topic + "-dlt", 0);
                    assertThat(context.getBean(JsonMapper.class)
                        .readValue(deadLetter.value(), NotificationCreateRequested.class)).isEqualTo(request);
                    assertThat(processingAttempts.get()).isGreaterThan(stage == HandoffStage.MAIN_DLT ? 1 : 4);
                });
        }
    }

    @Test
    void 인계_결과가_대기_시간_안에_완료되지_않으면_commit하지_않고_재처리한다() throws Exception {
        String topic = "creation-handoff-timeout-" + UUID.randomUUID();
        String group = "creation-group-" + UUID.randomUUID();
        var processor = mock(NotificationCreationProcessor.class);
        var attempts = new AtomicInteger();
        doAnswer(invocation -> {
            attempts.incrementAndGet();
            throw new TimeoutException();
        }).when(processor).process(any());
        try (Admin admin = admin(topic, 1)) {
            contextRunner(topic, group, processor)
                .withUserConfiguration(SpyTemplateConfiguration.class)
                .withPropertyValues("APP_NOTIFICATION_CREATION_ENABLED=true",
                    "APP_NOTIFICATION_RETRY_PUBLISH_RESULT_TIMEOUT_MS=100",
                    "KAFKA_PRODUCER_REQUEST_TIMEOUT_MS=500", "KAFKA_PRODUCER_DELIVERY_TIMEOUT_MS=1000")
                .run(context -> {
                    var template = context.getBean(KafkaTemplate.class);
                    var pending = new AtomicBoolean(true);
                    var entered = new CountDownLatch(1);
                    doAnswer(invocation -> {
                        ProducerRecord<?, ?> record = invocation.getArgument(0);
                        if (record.topic().equals(topic + "-retry") && pending.getAndSet(false)) {
                            entered.countDown();
                            return new CompletableFuture<>();
                        }
                        return invocation.callRealMethod();
                    }).when(template).send(any(ProducerRecord.class));
                    send(context, topic, 0, request("timeout", 1));
                    start(context, 1);
                    assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
                    await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                        assertThat(committed(admin, group, topic, 0)).isNull();
                        assertThat(endOffset(admin, topic + "-retry")).isZero();
                    });
                    await().atMost(Duration.ofSeconds(25))
                        .untilAsserted(() -> assertThat(committed(admin, group, topic, 0)).isEqualTo(1L));
                    assertThat(readRecord(topic + "-dlt", 0).value()).isNotEmpty();
                    assertThat(attempts.get()).isGreaterThan(3);
                });
        }
    }

    @Test
    void 웹훅_실패에도_DLT_기록과_입력_commit을_완료한다() throws Exception {
        String topic = "creation-alert-failure-" + UUID.randomUUID();
        String group = "creation-group-" + UUID.randomUUID();
        var processor = mock(NotificationCreationProcessor.class);
        doThrow(new IllegalArgumentException()).when(processor).process(any());
        try (Admin admin = admin(topic, 1)) {
            contextRunner(topic, group, processor).run(context -> {
                var client = context.getBean(DiscordWebhookClient.class);
                doThrow(new IllegalStateException("private-webhook")).when(client).send(any());
                send(context, topic, 0, request("alert-failure", 1));
                start(context, 1);
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                    assertThat(committed(admin, group, topic, 0)).isEqualTo(1L);
                    assertThat(endOffset(admin, topic + "-dlt")).isEqualTo(1);
                });
                var failure = ArgumentCaptor.forClass(NotificationFailureContext.class);
                verify(client).send(failure.capture());
                assertThat(failure.getValue().errorCode().name()).isEqualTo("DLT_RECORDED");
                assertThat(context.getBean(MeterRegistry.class).get("notification.alert.failures")
                    .tag("error_code", "ALERT_WEBHOOK_FAILED").counter().count()).isEqualTo(1);
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
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, KafkaAutoConfiguration.class,
                TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(SchedulingConfig.class, NotificationKafkaConfiguration.class,
                NotificationRetryConfiguration.class,
                NotificationCreationConsumer.class, NotificationRecoveryTestConfiguration.class,
                PropertiesConfiguration.class)
            .withBean(NotificationCreationProcessor.class, () -> processor)
            .withBean(NotificationCreationFailureClassifier.class, NotificationCreationFailureClassifier::new)
            .withBean(KafkaConnectionDetails.class, () -> () -> List.of(KAFKA.getBootstrapServers()))
            .withPropertyValues("NOTIFICATION_CREATE_REQUESTED_TOPIC=" + topic,
                "APP_NOTIFICATION_CREATION_GROUP_ID=" + group,
                "APP_NOTIFICATION_RETRY_MAX_ATTEMPTS=4",
                "APP_NOTIFICATION_RETRY_DELAY_MS=100",
                "APP_NOTIFICATION_RETRY_PUBLISH_RESULT_TIMEOUT_MS=5000")
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
            List<NewTopic> topics = new ArrayList<>();
            topics.add(new NewTopic(topic, partitions, (short) 1));
            topics.add(new NewTopic(topic + "-retry", partitions, (short) 1));
            topics.add(new NewTopic(topic + "-dlt", partitions, (short) 1));
            admin.createTopics(topics).all().get(15, TimeUnit.SECONDS);
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

    private Long committed(Admin admin, String group, String topic, int partition) throws Exception {
        OffsetAndMetadata offset = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
            .get(10, TimeUnit.SECONDS).get(new TopicPartition(topic, partition));
        return offset == null ? null : offset.offset();
    }

    private void send(AssertableApplicationContext context, String topic, int partition,
        NotificationCreateRequested request) throws Exception {
        context.getBean(KafkaTemplate.class).send(topic, partition, "7", request).get(10, TimeUnit.SECONDS);
    }

    private String groupFor(AssertableApplicationContext context, String topic) {
        return context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers().stream()
            .filter(container -> List.of(container.getContainerProperties().getTopics()).contains(topic))
            .findFirst().orElseThrow().getContainerProperties().getGroupId();
    }

    private long endOffset(Admin admin, String topic) throws Exception {
        var partition = new TopicPartition(topic, 0);
        return admin.listOffsets(Map.of(partition, OffsetSpec.latest()))
            .partitionResult(partition).get(10, TimeUnit.SECONDS).offset();
    }

    private ConsumerRecord<String, byte[]> readRecord(String topic, int partition) throws Exception {
        return readRecords(topic, partition, 1).getFirst();
    }

    private List<ConsumerRecord<String, byte[]>> readRecords(String topic, int partition, int count) throws Exception {
        try (var consumer = new KafkaConsumer<String, byte[]>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "read-" + UUID.randomUUID(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false))) {
            var topicPartition = new TopicPartition(topic, partition);
            consumer.assign(List.of(topicPartition));
            consumer.seekToBeginning(List.of(topicPartition));
            var received = new ArrayList<ConsumerRecord<String, byte[]>>();
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                var records = consumer.poll(Duration.ofMillis(200));
                records.forEach(received::add);
                if (received.size() >= count) {
                    return received.subList(0, count);
                }
            }
            throw new AssertionError("Expected a record in " + topic);
        }
    }

    private static NotificationCreateRequested request(String sourceEventId, int schemaVersion) {
        return new NotificationCreateRequested(schemaVersion, sourceEventId, 7L, NotificationType.LIKE,
            "제목", "본문", null, null, Instant.parse("2026-10-22T01:00:00Z"));
    }

    private static Stream<Exception> processingFailures() {
        return Stream.of(new DataAccessResourceFailureException("storage failure"),
            new ExecutionException(new IllegalStateException()));
    }

    private static Stream<Throwable> stoppingFailures() {
        return Stream.of(new InterruptedException(), new ExecutionException(new InterruptedException()),
            new AssertionError());
    }

    private enum InvalidPayload {
        MALFORMED_JSON, JSON_NULL, TOMBSTONE, UNSUPPORTED_SCHEMA
    }

    private enum HandoffStage {
        MAIN_RETRY("", "-retry", 1, 0),
        MAIN_DLT("", "-dlt", 1, 0),
        RETRY_RETRY("-retry", "-retry", 2, 0),
        RETRY_DLT("-retry", "-dlt", 4, 2);

        private final String inputSuffix;
        private final String destinationSuffix;
        private final int attempt;
        private final long inputOffset;

        HandoffStage(String inputSuffix, String destinationSuffix, int attempt, long inputOffset) {
            this.inputSuffix = inputSuffix;
            this.destinationSuffix = destinationSuffix;
            this.attempt = attempt;
            this.inputOffset = inputOffset;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SpyTemplateConfiguration {

        @Bean
        KafkaTemplate<Object, Object> kafkaTemplate(ProducerFactory<Object, Object> producerFactory) {
            return spy(new KafkaTemplate<>(producerFactory));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(
        {NotificationKafkaProperties.class, NotificationCreationProperties.class,
            NotificationRetryProperties.class}
    )
    static class PropertiesConfiguration {}
}
