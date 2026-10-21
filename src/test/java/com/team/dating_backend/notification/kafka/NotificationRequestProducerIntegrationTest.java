package com.team.dating_backend.notification.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.notification.config.NotificationKafkaConfiguration;
import com.team.dating_backend.notification.config.NotificationKafkaProperties;
import com.team.dating_backend.notification.config.NotificationPublishProperties;
import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.enums.NotificationType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class NotificationRequestProducerIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 실제_발행의_metadata와_key_JSON_타입_헤더_및_단일_기록을_확인한다(boolean hasTarget) {
        String topic = "notification-producer-test-" + UUID.randomUUID();
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, KafkaAutoConfiguration.class))
            .withUserConfiguration(NotificationKafkaConfiguration.class, NotificationRequestProducer.class,
                PropertiesConfiguration.class)
            .withBean(KafkaConnectionDetails.class, () -> () -> List.of(KAFKA.getBootstrapServers()))
            .withPropertyValues("app.notification.kafka.create-requested-topic=" + topic)
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
                var request = new NotificationCreateRequested(1, "LIKE_SENT:501", 101L, NotificationType.LIKE,
                    "좋아요", "새로운 알림", hasTarget ? "USER" : null, hasTarget ? 202L : null,
                    Instant.parse("2026-10-20T01:02:03.123456789Z"));
                var metadata = context.getBean(NotificationRequestProducer.class).publishAndAwait(request);
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
                    assertThat(record.key()).isEqualTo("101");
                    assertThat(record.partition()).isEqualTo(metadata.partition());
                    assertThat(record.offset()).isEqualTo(metadata.offset());
                    assertThat(record.headers().lastHeader("__TypeId__")).isNull();
                    assertThat(context.getBean(JsonMapper.class)
                        .readValue(record.value(), NotificationCreateRequested.class)).isEqualTo(request);
                    assertThat(consumer.endOffsets(partitions, Duration.ofSeconds(20)).values())
                        .containsExactlyInAnyOrder(0L, 1L);
                }
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({NotificationKafkaProperties.class, NotificationPublishProperties.class})
    static class PropertiesConfiguration {}
}
