package com.team.dating_backend.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.kafka.NotificationCreationConsumer;
import com.team.dating_backend.notification.kafka.NotificationRecoveryTestConfiguration;
import com.team.dating_backend.notification.kafka.NotificationCreationFailureClassifier;
import com.team.dating_backend.notification.service.NotificationCreationProcessor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
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
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonDelegatingErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.retrytopic.RetryTopicConfiguration;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import tools.jackson.databind.PropertyNamingStrategies;
import com.team.dating_backend.common.config.SchedulingConfig;

class NotificationKafkaConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, KafkaAutoConfiguration.class,
            TaskSchedulingAutoConfiguration.class))
        .withUserConfiguration(SchedulingConfig.class, NotificationKafkaConfiguration.class,
            NotificationRetryConfiguration.class, NotificationRecoveryTestConfiguration.class,
            PropertiesConfiguration.class)
        .withBean(NotificationCreationFailureClassifier.class, NotificationCreationFailureClassifier::new)
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
    void 생성_Listener는_공유_Group과_Retry_오류_정책을_상속하고_기본적으로_기동하지_않는다() {
        contextRunner.withUserConfiguration(NotificationCreationConsumer.class)
            .withBean(NotificationCreationProcessor.class, () -> mock(NotificationCreationProcessor.class))
            .run(context -> {
                assertThat(context).hasNotFailed();
                var container = context.getBean(KafkaListenerEndpointRegistry.class)
                    .getListenerContainer("notificationCreationConsumer");
                assertThat(container).isNotNull();
                assertThat(container.isAutoStartup()).isFalse();
                assertThat(container.isRunning()).isFalse();
                assertThat(container.getContainerProperties().getGroupId()).isEqualTo("local-notification-create-v1");
                assertThat(container.getContainerProperties().getTopics())
                    .containsExactly("local-notification-create-requested-v1");
                assertThat(container.getContainerProperties().getAckMode())
                    .isEqualTo(ContainerProperties.AckMode.RECORD);
                assertThat(container.getContainerProperties().isSyncCommits()).isTrue();
                assertThat(container.getContainerProperties().isCheckDeserExWhenValueNull()).isTrue();
                assertThat(((ConcurrentMessageListenerContainer<?, ?>) container).getCommonErrorHandler())
                    .isInstanceOf(CommonDelegatingErrorHandler.class)
                    .isNotInstanceOf(CommonContainerStoppingErrorHandler.class);
            });
    }

    @Test
    void Retry는_생성_요청에만_연결하고_시도별_목적지를_만든다() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var configuration = context.getBean("notificationRetryTopicConfiguration",
                RetryTopicConfiguration.class);
            assertThat(configuration.hasConfigurationForTopics(new String[]{"local-notification-create-requested-v1"}))
                .isTrue();
            assertThat(configuration.hasConfigurationForTopics(new String[]{"local-notification-delivery-v1"}))
                .isFalse();
            assertThat(configuration.getDestinationTopicProperties()).hasSize(8);
            assertThat(configuration.getDestinationTopicProperties()).extracting(destination -> destination.suffix())
                .containsExactly("", "-retry-0", "-retry-1", "-retry-2", "-retry-3", "-retry-4", "-retry-5", "-dlt");
            assertThat(configuration.getDestinationTopicProperties()).extracting(destination -> destination.delay())
                .containsExactly(0L, 1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 0L);
            assertThat(configuration.getConcurrency()).isEqualTo(1);
            var factory = context.getBean("notificationCreateRequestedKafkaListenerContainerFactory",
                ConcurrentKafkaListenerContainerFactory.class);
            assertThat(factory.createContainer("unrelated-topic").getCommonErrorHandler())
                .isInstanceOf(CommonContainerStoppingErrorHandler.class);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 9})
    void 실제_Listener는_시도_횟수에_따라_독립_Topic과_Group을_구성한다(int attempts) {
        contextRunner.withUserConfiguration(NotificationCreationConsumer.class)
            .withBean(NotificationCreationProcessor.class, () -> mock(NotificationCreationProcessor.class))
            .withPropertyValues("APP_NOTIFICATION_RETRY_MAX_ATTEMPTS=" + attempts)
            .run(context -> {
                assertThat(context).hasNotFailed();
                var containers = context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers();
                assertThat(containers).hasSize(attempts + 1);
                for (int i = 0; i < attempts - 1; i++) {
                    String suffix = "-retry-" + i;
                    var retry = containers.stream()
                        .filter(container -> java.util.List.of(container.getContainerProperties().getTopics())
                            .contains("local-notification-create-requested-v1" + suffix))
                        .findFirst().orElseThrow();
                    assertThat(retry.getContainerProperties().getGroupId())
                        .isEqualTo("local-notification-create-v1" + suffix);
                    assertThat(((ConcurrentMessageListenerContainer<?, ?>) retry).getConcurrency()).isEqualTo(1);
                    assertThat(retry.isAutoStartup()).isFalse();
                }
                var dlt = containers.stream()
                    .filter(container -> java.util.List.of(container.getContainerProperties().getTopics())
                        .contains("local-notification-create-requested-v1-dlt"))
                    .findFirst().orElseThrow();
                assertThat(dlt.isAutoStartup()).isFalse();
                assertThat(dlt.isRunning()).isFalse();
                assertThat(context.getBeansOfType(NewTopic.class)).isEmpty();
            });
    }

    @Test
    void 파생_Topic_이름이_길이_제한을_넘으면_시작을_거부한다() {
        contextRunner.withPropertyValues("NOTIFICATION_CREATE_REQUESTED_TOPIC=" + "a".repeat(242))
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(NotificationBusinessException.class);
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"-retry-0", "-dlt"})
    void 파생_Topic이_delivery와_겹치면_시작을_거부한다(String suffix) {
        contextRunner.withPropertyValues(
            "NOTIFICATION_DELIVERY_TOPIC=local-notification-create-requested-v1" + suffix)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(NotificationBusinessException.class);
            });
    }

    @Test
    void 생성_전용_환경변수는_공통과_delivery_설정에_영향_없이_적용한다() {
        contextRunner.withPropertyValues("APP_NOTIFICATION_CREATION_ENABLED=true",
            "APP_NOTIFICATION_CREATION_GROUP_ID=shared-creation", "APP_NOTIFICATION_CREATION_CONCURRENCY=2",
            "APP_NOTIFICATION_CREATION_MAX_POLL_RECORDS=5", "APP_NOTIFICATION_CREATION_MAX_POLL_INTERVAL_MS=90000",
            "spring.kafka.consumer.enable-auto-commit=true", "spring.kafka.listener.ack-mode=batch")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var factory = context.getBean("notificationCreateRequestedKafkaListenerContainerFactory",
                    ConcurrentKafkaListenerContainerFactory.class);
                var container = (ConcurrentMessageListenerContainer<?, ?>) factory.createContainer("test-topic");
                assertThat(container.isAutoStartup()).isTrue();
                assertThat(container.getConcurrency()).isEqualTo(2);
                assertThat(container.getContainerProperties().getGroupId()).isEqualTo("shared-creation");
                assertThat(container.getContainerProperties().getAckMode())
                    .isEqualTo(ContainerProperties.AckMode.RECORD);
                assertThat(factory.getConsumerFactory().getConfigurationProperties())
                    .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                    .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 5)
                    .containsEntry(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 90_000);
                var delivery = context.getBean("notificationDeliveryKafkaListenerContainerFactory",
                    ConcurrentKafkaListenerContainerFactory.class);
                assertThat(delivery.getContainerProperties().getGroupId()).startsWith("local-notification-sse-v1-");
                assertThat(delivery.getContainerProperties().getAckMode())
                    .isEqualTo(ContainerProperties.AckMode.RECORD);
                assertThat(delivery.getConsumerFactory().getConfigurationProperties())
                    .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                    .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1)
                    .containsEntry(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000);
                assertThat(context.getBean("kafkaConsumerFactory", ConsumerFactory.class).getConfigurationProperties())
                    .doesNotContainKeys(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
                        ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG);
            });
    }

    @Test
    void delivery_전용_설정과_profile_기반_Group은_공통_Consumer_설정과_분리한다() {
        contextRunner.withPropertyValues("spring.profiles.active=prod", "APP_NOTIFICATION_DELIVERY_ENABLED=true",
            "APP_NOTIFICATION_DELIVERY_CONCURRENCY=3", "APP_NOTIFICATION_DELIVERY_MAX_POLL_RECORDS=4",
            "APP_NOTIFICATION_DELIVERY_MAX_POLL_INTERVAL_MS=120000",
            "spring.kafka.consumer.enable-auto-commit=true", "spring.kafka.listener.ack-mode=batch")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var factory = context.getBean("notificationDeliveryKafkaListenerContainerFactory",
                    ConcurrentKafkaListenerContainerFactory.class);
                var container = (ConcurrentMessageListenerContainer<?, ?>) factory.createContainer("test-topic");
                assertThat(container.isAutoStartup()).isTrue();
                assertThat(container.getConcurrency()).isEqualTo(3);
                assertThat(container.getContainerProperties().getGroupId()).startsWith("prod-notification-sse-v1-");
                assertThat(container.getContainerProperties().getAckMode())
                    .isEqualTo(ContainerProperties.AckMode.RECORD);
                assertThat(container.getContainerProperties().isSyncCommits()).isTrue();
                assertThat(factory.getConsumerFactory().getConfigurationProperties())
                    .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                    .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
                    .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 4)
                    .containsEntry(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 120_000);
                assertThat(context.getBean("kafkaConsumerFactory", ConsumerFactory.class).getConfigurationProperties())
                    .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true)
                    .doesNotContainKeys(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
                        ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG);
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
    void byte_array는_변경없이_원시_바이트로_발행한다() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var serializer = context.getBean(DefaultKafkaProducerFactory.class).getValueSerializer();
            byte[] value = {0x00, 0x01, (byte) 0xff, 0x20};
            var headers = new RecordHeaders();

            assertThat(serializer.serialize("test-topic", headers, value)).isEqualTo(value);
            assertThat(headers).isEmpty();
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
    @EnableConfigurationProperties(
        {NotificationKafkaProperties.class, NotificationPublishProperties.class,
            NotificationCreationProperties.class, NotificationRetryProperties.class}
    )
    static class PropertiesConfiguration {}
}
