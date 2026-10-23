package com.team.dating_backend.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.team.dating_backend.notification.kafka.NotificationRecoveryTestConfiguration;
import com.team.dating_backend.notification.kafka.NotificationCreationFailureClassifier;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.enums.NotificationType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
class NotificationKafkaConnectionIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    @Test
    void 생성_요청은_기존_메시지부터_소비하고_두_파티션의_새_메시지를_복원한다() {
        var message = new NotificationCreateRequested(1, "source-create", 101L, NotificationType.LIKE,
            "좋아요", "새로운 알림", null, null, Instant.parse("2026-10-20T01:02:03.123456Z"));
        assertRoundTrip("notificationCreateRequestedKafkaListenerContainerFactory", message, true);
    }

    @Test
    void delivery는_시작_이후_두_파티션의_새_메시지만_소비한다() {
        var message = new NotificationDeliveryEvent(1, "source-delivery", 101L,
            new NotificationEventResponse(501L, "LIKE", "좋아요", "새로운 알림", null, null, null,
                LocalDateTime.parse("2026-10-20T10:02:03.123456")));
        assertRoundTrip("notificationDeliveryKafkaListenerContainerFactory", message, false);
    }

    private void assertRoundTrip(String factoryName, Object message, boolean readsHistory) {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, KafkaAutoConfiguration.class))
            .withUserConfiguration(NotificationKafkaConfiguration.class, NotificationRecoveryTestConfiguration.class)
            .withBean(NotificationCreationProperties.class, NotificationCreationProperties::new)
            .withBean(NotificationCreationFailureClassifier.class, NotificationCreationFailureClassifier::new)
            .withBean(KafkaConnectionDetails.class, () -> () -> List.of(KAFKA.getBootstrapServers()))
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
            })
            .run(context -> {
                assertThat(context).hasNotFailed();
                String topic = "notification-config-test-" + UUID.randomUUID();
                try (Admin admin = Admin.create(context.getBean(KafkaAdmin.class).getConfigurationProperties())) {
                    admin.createTopics(List.of(new NewTopic(topic, 2, (short) 1)))
                        .all().get(20, TimeUnit.SECONDS);
                }
                KafkaTemplate<Object, Object> template = context.getBean(KafkaTemplate.class);
                template.send(topic, 0, "history", message).get(20, TimeUnit.SECONDS);
                ConcurrentKafkaListenerContainerFactory<?, ?> factory = context.getBean(factoryName,
                    ConcurrentKafkaListenerContainerFactory.class);
                try (var consumer = factory.getConsumerFactory().createConsumer("test-" + UUID.randomUUID())) {
                    var first = new TopicPartition(topic, 0);
                    var second = new TopicPartition(topic, 1);
                    consumer.assign(List.of(first, second));
                    assertThat(consumer.position(first, Duration.ofSeconds(20))).isEqualTo(readsHistory ? 0L : 1L);
                    assertThat(consumer.position(second, Duration.ofSeconds(20))).isZero();
                    template.send(topic, 0, "new-0", message).get(20, TimeUnit.SECONDS);
                    template.send(topic, 1, "new-1", message).get(20, TimeUnit.SECONDS);
                    List<ConsumerRecord<?, ?>> records = new ArrayList<>();
                    int expectedCount = readsHistory ? 3 : 2;
                    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                    while (records.size() < expectedCount && System.nanoTime() < deadline) {
                        consumer.poll(Duration.ofMillis(200)).forEach(records::add);
                    }
                    assertThat(records).hasSize(expectedCount);
                    assertThat(records).extracting(ConsumerRecord::partition).contains(0, 1);
                    assertThat(records).extracting(ConsumerRecord::key, ConsumerRecord::value)
                        .contains(tuple("new-0", message), tuple("new-1", message));
                    assertThat(records.stream().anyMatch(record -> "history".equals(record.key())))
                        .isEqualTo(readsHistory);
                    assertThat(records).allSatisfy(record -> {
                        assertThat(record.value()).isEqualTo(message);
                        assertThat(record.headers().lastHeader("__TypeId__")).isNull();
                    });
                }
            });
    }
}
