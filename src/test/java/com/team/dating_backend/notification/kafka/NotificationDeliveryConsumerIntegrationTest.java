package com.team.dating_backend.notification.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.team.dating_backend.notification.alert.DiscordWebhookClient;
import com.team.dating_backend.notification.alert.NotificationFailureContext;
import com.team.dating_backend.notification.config.NotificationCreationProperties;
import com.team.dating_backend.notification.config.NotificationKafkaConfiguration;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import com.team.dating_backend.notification.exception.NotificationInvalidDeliveryEventException;
import com.team.dating_backend.notification.service.NotificationDeliveryEventValidator;
import com.team.dating_backend.notification.sse.NotificationEmitterRegistry;
import com.team.dating_backend.notification.sse.NotificationSseConnection;
import com.team.dating_backend.notification.sse.NotificationSseProcessIdentity;
import com.team.dating_backend.notification.sse.NotificationDeliveryReadinessTracker;
import com.team.dating_backend.notification.sse.NotificationSseSender;
import com.team.dating_backend.notification.sse.NotificationSseObservation;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
class NotificationDeliveryConsumerIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @Test
    void 프로세스별_Group은_모든_partition을_독립_수신하고_로컬_연결에_전파하며_재시작은_commit부터_이어간다() throws Exception {
        String topic = "delivery-groups-" + UUID.randomUUID();
        try (Admin admin = admin(topic, 2)) {
            runner(topic).withPropertyValues("APP_NOTIFICATION_DELIVERY_CONCURRENCY=2").run(first -> {
                send(first, topic, 0, event(10));
                runner(topic).withPropertyValues("APP_NOTIFICATION_DELIVERY_CONCURRENCY=2").run(second -> {
                    var firstEvents = connection(first, 7L);
                    var anotherConnection = connection(first, 7L);
                    var secondEvents = connection(second, 7L);
                    var unrelatedEvents = connection(first, 8L);
                    String firstGroup = group(first);
                    String secondGroup = group(second);
                    assertThat(firstGroup).startsWith("shared-notification-sse-").isNotEqualTo(secondGroup);
                    assertThat(listener(first).isRunning()).isFalse();
                    start(first, 2);
                    start(second, 2);
                    assertThat(listener(first).getContainers()).hasSize(2);
                    assertThat(listener(second).getContainers()).hasSize(2);
                    send(first, topic, 0, event(11));
                    send(first, topic, 1, event(12));
                    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                        assertThat(firstEvents).containsExactlyInAnyOrder(event(11).notification(),
                            event(12).notification());
                        assertThat(anotherConnection).containsExactlyInAnyOrderElementsOf(firstEvents);
                        assertThat(secondEvents).containsExactlyInAnyOrderElementsOf(firstEvents);
                        assertThat(unrelatedEvents).isEmpty();
                        assertThat(committed(admin, firstGroup, topic, 0)).isEqualTo(2L);
                        assertThat(committed(admin, firstGroup, topic, 1)).isEqualTo(1L);
                        assertThat(committed(admin, secondGroup, topic, 0)).isEqualTo(2L);
                        assertThat(committed(admin, secondGroup, topic, 1)).isEqualTo(1L);
                    });
                    listener(first).stop();
                    send(second, topic, 0, event(13));
                    await().atMost(Duration.ofSeconds(15))
                        .untilAsserted(() -> assertThat(committed(admin, secondGroup, topic, 0)).isEqualTo(3L));
                    start(first, 2);
                    await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                        assertThat(firstEvents).hasSize(3).contains(event(13).notification());
                        assertThat(anotherConnection).hasSize(3).contains(event(13).notification());
                        assertThat(committed(admin, firstGroup, topic, 0)).isEqualTo(3L);
                    });
                    assertThat(group(first)).isEqualTo(firstGroup);
                });
            });
        }
    }

    @Test
    void 역직렬화와_계약_실패는_Discord_실패에도_해당_record만_commit하고_다음_정상_record를_처리한다() throws Exception {
        String topic = "delivery-invalid-" + UUID.randomUUID();
        try (Admin admin = admin(topic, 1)) {
            runner(topic).run(context -> {
                var events = connection(context, 7L);
                var client = context.getBean(DiscordWebhookClient.class);
                doThrow(new IllegalStateException()).when(client).send(any());
                start(context, 1);
                send(context, topic, 0, new byte[]{'{'});
                send(context, topic, 0, new byte[]{'n', 'u', 'l', 'l'});
                send(context, topic, 0, null);
                send(context, topic, 0, new NotificationDeliveryEvent(99, "bad-schema", 7L, event(20).notification()));
                send(context, topic, 0, event(21));
                send(context, topic, 0,
                    new NotificationDeliveryEvent(1, "no-connection", 9L, event(22).notification()));
                await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(() -> assertThat(committed(admin, group(context), topic, 0)).isEqualTo(6L));
                assertThat(events).containsExactly(event(21).notification());
                var captor = ArgumentCaptor.forClass(NotificationFailureContext.class);
                verify(client, times(4)).send(captor.capture());
                var failures = captor.getAllValues();
                assertThat(failures).extracting(NotificationFailureContext::errorCode)
                    .containsExactly(NotificationFailureCode.DELIVERY_DESERIALIZATION_FAILED,
                        NotificationFailureCode.DELIVERY_INVALID_EVENT,
                        NotificationFailureCode.DELIVERY_INVALID_EVENT,
                        NotificationFailureCode.DELIVERY_INVALID_EVENT);
                assertThat(failures.get(0).deliveryDetails()).isNull();
                assertThat(failures.get(0).kafkaDetails().inputLocation().offset()).isZero();
                assertThat(failures.get(3).deliveryDetails().notificationId()).isEqualTo(20L);
                assertThat(failures.get(3).kafkaDetails().inputLocation().offset()).isEqualTo(3L);
                assertThat(failures.get(1).kafkaDetails().consumerGroup()).isEqualTo(group(context));
                var meters = context.getBean(MeterRegistry.class);
                assertThat(meters.get("notification.failures").tag("error_code", "DELIVERY_INVALID_EVENT")
                    .counter().count()).isEqualTo(3);
                assertThat(meters.get("notification.failures").tag("error_code", "DELIVERY_DESERIALIZATION_FAILED")
                    .counter().count()).isEqualTo(1);
                assertThat(meters.get("notification.alert.failures").tag("error_code", "ALERT_WEBHOOK_FAILED")
                    .counter().count()).isEqualTo(4);
                assertThat(listener(context).isRunning()).isTrue();
            });
        }
    }

    @Test
    void 알_수_없는_처리_오류는_보고하고_실패_record와_후속_record를_commit하지_않은_채_중단한다() throws Exception {
        String topic = "delivery-stop-" + UUID.randomUUID();
        try (Admin admin = admin(topic, 1)) {
            runner(topic).run(context -> {
                doThrow(new IllegalStateException()).when(context.getBean(NotificationSseSender.class))
                    .sendNotification(eq(7L), any());
                start(context, 1);
                send(context, topic, 0, event(30));
                send(context, topic, 0, event(31));
                await().atMost(Duration.ofSeconds(20)).until(() -> !listener(context).isRunning());
                assertThat(committed(admin, group(context), topic, 0)).isEqualTo(0L);
                verify(context.getBean(NotificationSseSender.class)).sendNotification(7L, event(30).notification());
                verifyNoMoreInteractions(context.getBean(NotificationSseSender.class));
                var captor = ArgumentCaptor.forClass(NotificationFailureContext.class);
                verify(context.getBean(DiscordWebhookClient.class)).send(captor.capture());
                assertThat(captor.getValue().errorCode()).isEqualTo(NotificationFailureCode.DELIVERY_PROCESSING_FAILED);
            });
        }
    }

    @ParameterizedTest
    @MethodSource("fatalCauses")
    void 계약_예외에_감싸진_인터럽트와_Error도_보고나_commit_없이_중단한다(Throwable cause) throws Exception {
        String topic = "delivery-fatal-" + UUID.randomUUID();
        try (Admin admin = admin(topic, 1)) {
            runner(topic).run(context -> {
                doThrow(new NotificationInvalidDeliveryEventException(cause))
                    .when(context.getBean(NotificationSseSender.class)).sendNotification(eq(7L), any());
                start(context, 1);
                send(context, topic, 0, event(40));
                send(context, topic, 0, event(41));
                await().atMost(Duration.ofSeconds(20)).until(() -> !listener(context).isRunning());
                assertThat(committed(admin, group(context), topic, 0)).isEqualTo(0L);
                verify(context.getBean(NotificationSseSender.class)).sendNotification(7L, event(40).notification());
                verifyNoMoreInteractions(context.getBean(NotificationSseSender.class));
                verifyNoInteractions(context.getBean(DiscordWebhookClient.class));
            });
        }
    }

    private ApplicationContextRunner runner(String topic) {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, KafkaAutoConfiguration.class))
            .withUserConfiguration(NotificationKafkaConfiguration.class, NotificationDeliveryConsumer.class,
                NotificationDeliveryEventValidator.class, NotificationRecoveryTestConfiguration.class,
                TestApplication.class)
            .withBean(NotificationCreationFailureClassifier.class, NotificationCreationFailureClassifier::new)
            .withBean(KafkaConnectionDetails.class, () -> () -> List.of(KAFKA.getBootstrapServers()))
            .withPropertyValues("NOTIFICATION_DELIVERY_TOPIC=" + topic,
                "APP_NOTIFICATION_DELIVERY_ENABLED=false",
                "APP_NOTIFICATION_DELIVERY_GROUP_ID_PREFIX=shared-notification-sse",
                "APP_NOTIFICATION_DELIVERY_MAX_POLL_RECORDS=10")
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
        var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
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
            .getListenerContainer("notificationDeliveryConsumer");
    }

    private void start(AssertableApplicationContext context, int partitions) {
        var container = listener(context);
        container.start();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(container.getAssignedPartitions()).hasSize(partitions);
            assertThat(context.getBean(NotificationDeliveryReadinessTracker.class).isReady()).isTrue();
        });
    }

    private String group(AssertableApplicationContext context) {
        return context.getBean(NotificationSseProcessIdentity.class).getGroupId();
    }

    private Long committed(Admin admin, String group, String topic, int partition) throws Exception {
        OffsetAndMetadata offset = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
            .get(10, TimeUnit.SECONDS).get(new TopicPartition(topic, partition));
        return offset == null ? null : offset.offset();
    }

    @SuppressWarnings("unchecked")
    private void send(AssertableApplicationContext context, String topic, int partition, Object value)
        throws Exception {
        context.getBean(KafkaTemplate.class).send(topic, partition, "7", value).get(10, TimeUnit.SECONDS);
    }

    private ConcurrentLinkedQueue<NotificationEventResponse> connection(AssertableApplicationContext context,
        Long userId)
        throws IOException {
        var emitter = mock(SseEmitter.class);
        var events = new ConcurrentLinkedQueue<NotificationEventResponse>();
        doAnswer(invocation -> {
            SseEmitter.SseEventBuilder builder = invocation.getArgument(0);
            builder.build().stream().map(data -> data.getData())
                .filter(NotificationEventResponse.class::isInstance)
                .map(NotificationEventResponse.class::cast).forEach(events::add);
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        context.getBean(NotificationEmitterRegistry.class).register(
            new NotificationSseConnection(userId, UUID.randomUUID().toString(), emitter, LocalDateTime.now()));
        return events;
    }

    private static NotificationDeliveryEvent event(long id) {
        return new NotificationDeliveryEvent(1, "source-" + id, 7L,
            new NotificationEventResponse(id, "LIKE", "제목", "본문", null, null, null,
                LocalDateTime.of(2026, 10, 23, 12, 0)));
    }

    private static Stream<Throwable> fatalCauses() {
        return Stream.of(new InterruptedException(), new AssertionError());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableKafka
    @EnableConfigurationProperties(NotificationCreationProperties.class)
    static class TestApplication {

        @Bean
        NotificationSseObservation observation() {
            return mock(NotificationSseObservation.class);
        }

        @Bean
        NotificationEmitterRegistry registry(NotificationSseObservation observation) {
            return new NotificationEmitterRegistry(observation);
        }

        @Bean
        NotificationSseSender sender(NotificationEmitterRegistry registry, NotificationSseObservation observation) {
            return spy(new NotificationSseSender(registry, observation));
        }
    }
}
