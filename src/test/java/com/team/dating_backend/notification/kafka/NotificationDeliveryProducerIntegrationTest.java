package com.team.dating_backend.notification.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.notification.config.NotificationCreationProperties;
import com.team.dating_backend.notification.config.NotificationKafkaConfiguration;
import com.team.dating_backend.notification.config.NotificationKafkaProperties;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class NotificationDeliveryProducerIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @Test
    void 실제_delivery_발행의_key와_저장_snapshot_JSON_metadata_및_단일_기록을_확인한다() {
        String topic = "notification-delivery-test-" + UUID.randomUUID();
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, KafkaAutoConfiguration.class))
            .withUserConfiguration(NotificationKafkaConfiguration.class, NotificationDeliveryProducer.class,
                PropertiesConfiguration.class)
            .withBean(KafkaConnectionDetails.class, () -> () -> List.of(KAFKA.getBootstrapServers()))
            .withPropertyValues("app.notification.kafka.delivery-topic=" + topic)
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
                try (Admin admin = Admin.create(context.getBean(KafkaAdmin.class).getConfigurationProperties())) {
                    admin.createTopics(List.of(new NewTopic(topic, 2, (short) 1)))
                        .all().get(20, TimeUnit.SECONDS);
                }
                var createdAt = LocalDateTime.of(2026, 10, 21, 1, 2, 3, 123456000);
                var notification = new NotificationEventResponse(
                    10L, "LIKE", "저장된 제목", "저장된 본문", null, null, createdAt.plusMinutes(1), createdAt);
                var event = new NotificationDeliveryEvent(1, "event-1", 7L, notification);
                var metadata = context.getBean(NotificationDeliveryProducer.class).publishAndAwait(event);
                assertThat(metadata.topic()).isEqualTo(topic);
                assertThat(metadata.partition()).isIn(0, 1);
                assertThat(metadata.offset()).isZero();

                ConsumerFactory<?, ?> factory = context.getBean(ConsumerFactory.class);
                var consumerProperties = new HashMap<>(factory.getConfigurationProperties());
                consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
                consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
                var partitions = List.of(new TopicPartition(topic, 0), new TopicPartition(topic, 1));
                try (var consumer = new KafkaConsumer<String, String>(consumerProperties)) {
                    consumer.assign(partitions);
                    consumer.seekToBeginning(partitions);
                    var records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(20), 1);
                    assertThat(records.count()).isEqualTo(1);
                    var record = records.iterator().next();
                    assertThat(record.key()).isEqualTo("7");
                    assertThat(record.partition()).isEqualTo(metadata.partition());
                    assertThat(record.offset()).isEqualTo(metadata.offset());
                    assertThat(record.headers().lastHeader("__TypeId__")).isNull();
                    assertThat(context.getBean(JsonMapper.class)
                        .readValue(record.value(), NotificationDeliveryEvent.class)).isEqualTo(event);
                    assertThat(consumer.endOffsets(partitions, Duration.ofSeconds(20)).values())
                        .containsExactlyInAnyOrder(0L, 1L);
                }
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({NotificationKafkaProperties.class, NotificationCreationProperties.class})
    static class PropertiesConfiguration {}
}
