package com.team.dating_backend.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.enums.NotificationType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import tools.jackson.databind.PropertyNamingStrategies;

class NotificationKafkaConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, KafkaAutoConfiguration.class))
        .withUserConfiguration(NotificationKafkaConfiguration.class,
            PropertiesConfiguration.class)
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

    @Test
    void 실제_YAML의_공통_안전_설정과_토픽별_소비_설정을_적용한다() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            KafkaProperties properties = context.getBean(KafkaProperties.class);
            assertThat(properties.getBootstrapServers())
                .containsExactly("localhost:29092", "localhost:39092", "localhost:49092");
            assertThat(properties.getAdmin().isAutoCreate()).isFalse();
            NotificationKafkaProperties topics = context.getBean(NotificationKafkaProperties.class);
            assertThat(topics.getCreateRequestedTopic()).isEqualTo("local-notification-create-requested-v1");
            assertThat(topics.getDeliveryTopic()).isEqualTo("local-notification-delivery-v1");
            assertThat(properties.buildProducerProperties())
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
            assertThat(properties.buildConsumerProperties())
                .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .containsEntry(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false")
                .doesNotContainKeys(ConsumerConfig.GROUP_ID_CONFIG, ConsumerConfig.AUTO_OFFSET_RESET_CONFIG);
            assertFactory(context.getBean("notificationCreateRequestedKafkaListenerContainerFactory",
                ConcurrentKafkaListenerContainerFactory.class), NotificationCreateRequested.class, "earliest");
            assertFactory(context.getBean("notificationDeliveryKafkaListenerContainerFactory",
                ConcurrentKafkaListenerContainerFactory.class), NotificationDeliveryEvent.class, "latest");
            assertThat(context.getBean("kafkaConsumerFactory", ConsumerFactory.class).getConfigurationProperties())
                .doesNotContainKeys(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                    JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS);
        });
    }

    @Test
    void YAML의_발행_timeout_기본값은_Kafka_제약과_결과_대기_예산을_만족한다() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var configuration = ProducerConfig.configDef()
                .parse(context.getBean(KafkaProperties.class).buildProducerProperties());
            assertThat(configuration)
                .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3_000L)
                .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000)
                .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
            long deliveryTimeout = ((Number) configuration.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG)).longValue();
            long requestTimeout = ((Number) configuration.get(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG)).longValue();
            long linger = ((Number) configuration.get(ProducerConfig.LINGER_MS_CONFIG)).longValue();
            assertThat(deliveryTimeout).isGreaterThanOrEqualTo(requestTimeout + linger);
            assertThat(context.getBean(NotificationPublishProperties.class).getResultTimeoutMs())
                .isEqualTo(15_000L).isGreaterThan(deliveryTimeout);
        });
    }

    @Test
    void 환경_변수로_Kafka_timeout과_발행_결과_대기_시간을_바꿀_수_있다() {
        contextRunner.withPropertyValues(
            "KAFKA_PRODUCER_MAX_BLOCK_MS=5000",
            "KAFKA_PRODUCER_REQUEST_TIMEOUT_MS=4000",
            "KAFKA_PRODUCER_DELIVERY_TIMEOUT_MS=20000",
            "APP_NOTIFICATION_PUBLISH_RESULT_TIMEOUT_MS=25000")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(ProducerConfig.configDef()
                    .parse(context.getBean(KafkaProperties.class).buildProducerProperties()))
                    .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000L)
                    .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 4_000)
                    .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 20_000);
                assertThat(context.getBean(NotificationPublishProperties.class).getResultTimeoutMs())
                    .isEqualTo(25_000L);
            });
    }

    @Test
    void 배포_환경_변수로_브로커와_토픽을_바꿀_수_있다() {
        contextRunner.withPropertyValues(
            "KAFKA_BOOTSTRAP_SERVERS=broker.internal:9092",
            "NOTIFICATION_CREATE_REQUESTED_TOPIC=prod-notification-create-requested-v1",
            "NOTIFICATION_DELIVERY_TOPIC=prod-notification-delivery-v1")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(KafkaProperties.class).getBootstrapServers())
                    .containsExactly("broker.internal:9092");
                NotificationKafkaProperties topics = context.getBean(NotificationKafkaProperties.class);
                assertThat(topics.getCreateRequestedTopic()).isEqualTo("prod-notification-create-requested-v1");
                assertThat(topics.getDeliveryTopic()).isEqualTo("prod-notification-delivery-v1");
            });
    }

    @Test
    void 두_메시지를_타입_헤더_없이_변환하고_외부_타입_헤더는_무시한다() {
        contextRunner.run(context -> {
            var producerFactory = context.getBean(DefaultKafkaProducerFactory.class);
            Serializer<Object> serializer = producerFactory.getValueSerializer();
            var requested = new NotificationCreateRequested(1, "source-1", 101L, NotificationType.LIKE,
                "좋아요", "새로운 알림", null, null, Instant.parse("2026-10-20T01:02:03.123456Z"));
            var delivery = new NotificationDeliveryEvent(1, "source-1", 101L,
                new NotificationEventResponse(501L, "LIKE", "좋아요", "새로운 알림", null, null, null,
                    LocalDateTime.parse("2026-10-20T10:02:03.123456")));
            Map<String, Object> messages = Map.of(
                "notificationCreateRequestedKafkaListenerContainerFactory", requested,
                "notificationDeliveryKafkaListenerContainerFactory", delivery);
            messages.forEach((factoryName, message) -> {
                var factory = context.getBean(factoryName, ConcurrentKafkaListenerContainerFactory.class);
                Deserializer<?> deserializer = valueDeserializer(factory);
                var headers = new RecordHeaders();
                byte[] json = serializer.serialize("test-topic", headers, message);
                assertThat(headers.lastHeader("__TypeId__")).isNull();
                headers.add("__TypeId__", "untrusted.UnknownPayload".getBytes(StandardCharsets.UTF_8));
                assertThat(deserializer.deserialize("test-topic", headers, json)).isEqualTo(message);
                assertThat(headers.lastHeader(KafkaUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER)).isNull();
                deserializer.close();
            });
            serializer.close();
        });
    }

    @Test
    void 잘못된_JSON은_역직렬화_오류_헤더로_전달한다() {
        contextRunner.run(context -> {
            var factory = context.getBean("notificationCreateRequestedKafkaListenerContainerFactory",
                ConcurrentKafkaListenerContainerFactory.class);
            var deserializer = valueDeserializer(factory);
            var headers = new RecordHeaders();
            assertThat(deserializer.deserialize("test-topic", headers, "{invalid".getBytes(StandardCharsets.UTF_8)))
                .isNull();
            assertThat(headers.lastHeader(KafkaUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER)).isNotNull();
            deserializer.close();
        });
    }

    @Test
    void 생산자와_소비자가_Boot에서_구성한_동일한_매퍼_설정을_사용한다() {
        contextRunner.withBean(JsonMapperBuilderCustomizer.class,
            () -> builder -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE))
            .run(context -> {
                var message = new NotificationCreateRequested(1, "custom-mapper", 101L, NotificationType.NOTICE,
                    "공지", null, null, null, Instant.parse("2026-10-20T01:02:03Z"));
                var serializer = context.getBean(DefaultKafkaProducerFactory.class).getValueSerializer();
                byte[] json = serializer.serialize("test-topic", message);
                assertThat(new String(json, StandardCharsets.UTF_8)).contains("\"source_event_id\"");
                var factory = context.getBean("notificationCreateRequestedKafkaListenerContainerFactory",
                    ConcurrentKafkaListenerContainerFactory.class);
                var deserializer = valueDeserializer(factory);
                assertThat(deserializer.deserialize("test-topic", new RecordHeaders(), json)).isEqualTo(message);
                deserializer.close();
                serializer.close();
            });
    }

    private void assertFactory(ConcurrentKafkaListenerContainerFactory<?, ?> factory,
        Class<?> payloadType, String resetPolicy) {
        assertThat(factory.isBatchListener()).isFalse();
        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
        assertThat(factory.getConsumerFactory().getConfigurationProperties())
            .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, resetPolicy)
            .containsEntry(JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, payloadType)
            .containsEntry(JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS, false)
            .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
            .doesNotContainKey(ConsumerConfig.GROUP_ID_CONFIG);
        var deserializer = valueDeserializer(factory);
        assertThat(deserializer).isInstanceOf(ErrorHandlingDeserializer.class);
        deserializer.close();
    }

    private Deserializer<?> valueDeserializer(ConcurrentKafkaListenerContainerFactory<?, ?> factory) {
        Deserializer<?> deserializer = factory.getConsumerFactory().getValueDeserializer();
        deserializer.configure(factory.getConsumerFactory().getConfigurationProperties(), false);
        return deserializer;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({NotificationKafkaProperties.class, NotificationPublishProperties.class})
    static class PropertiesConfiguration {}
}
