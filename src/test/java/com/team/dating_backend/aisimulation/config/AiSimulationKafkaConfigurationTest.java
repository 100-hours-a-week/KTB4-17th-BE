package com.team.dating_backend.aisimulation.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.aisimulation.dto.event.AiSimulationCompletedEvent;
import com.team.dating_backend.aisimulation.dto.event.AiSimulationRequestedEvent;
import com.team.dating_backend.aisimulation.service.AiSimulationEventFactory;
import com.team.dating_backend.notification.config.NotificationKafkaConfiguration;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.LongSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import tools.jackson.databind.json.JsonMapper;

class AiSimulationKafkaConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, KafkaAutoConfiguration.class))
        .withUserConfiguration(AiSimulationKafkaConfiguration.class, PropertiesConfiguration.class)
        .withPropertyValues("spring.kafka.admin.auto-create=false");

    @Test
    void 다섯_업무의_컨테이너가_그룹과_poll_설정을_분리한다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(AiSimulationKafkaProperties.class);
            var groups = Map.of(
                "Generation", properties.getGeneration(),
                "RequestNotification", properties.getRequestNotification(),
                "Recommendation", properties.getRecommendation(),
                "RequesterNotification", properties.getRequesterNotification(),
                "TargetNotification", properties.getTargetNotification());
            groups.forEach((name, group) -> {
                var factory = context.getBean("aiSimulation" + name + "KafkaListenerContainerFactory",
                    ConcurrentKafkaListenerContainerFactory.class);
                var container = (ConcurrentMessageListenerContainer<?, ?>) factory.createContainer("unused-topic");
                assertThat(container.getConcurrency()).isEqualTo(2);
                assertThat(container.isAutoStartup()).isFalse();
                assertThat(container.getContainerProperties().getGroupId()).isEqualTo(group.getGroupId());
                assertThat(container.getContainerProperties().getAckMode())
                    .isEqualTo(ContainerProperties.AckMode.RECORD);
                assertThat(container.getContainerProperties().isSyncCommits()).isTrue();
                assertThat(container.getContainerProperties().isCheckDeserExWhenKeyNull()).isTrue();
                assertThat(container.getContainerProperties().isCheckDeserExWhenValueNull()).isTrue();
                assertThat(factory.getConsumerFactory().getConfigurationProperties())
                    .containsEntry(JacksonJsonDeserializer.VALUE_DEFAULT_TYPE,
                        name.equals("RequesterNotification") || name.equals("TargetNotification")
                            ? AiSimulationCompletedEvent.class
                            : AiSimulationRequestedEvent.class)
                    .containsEntry(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,
                        name.equals("Generation") ? 180000 : 300000)
                    .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1)
                    .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                    .containsEntry(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
            });
            assertThat(context.getBean("kafkaConsumerFactory", ConsumerFactory.class).getConfigurationProperties())
                .doesNotContainKeys(JacksonJsonDeserializer.VALUE_DEFAULT_TYPE,
                    ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG);
        });
    }

    @Test
    void 외부_설정을_해당_업무_컨테이너에_적용한다() {
        runner.withPropertyValues(
            "app.ai-simulation.kafka.generation.enabled=true",
            "app.ai-simulation.kafka.generation.group-id=test-generation",
            "app.ai-simulation.kafka.generation-poll.max-poll-interval-ms=200000",
            "app.ai-simulation.kafka.concurrency=3").run(context -> {
                var factory = context.getBean("aiSimulationGenerationKafkaListenerContainerFactory",
                    ConcurrentKafkaListenerContainerFactory.class);
                var container = (ConcurrentMessageListenerContainer<?, ?>) factory.createContainer("unused-topic");
                assertThat(container.isAutoStartup()).isTrue();
                assertThat(container.getConcurrency()).isEqualTo(3);
                assertThat(container.getContainerProperties().getGroupId()).isEqualTo("test-generation");
                assertThat(factory.getConsumerFactory().getConfigurationProperties())
                    .containsEntry(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 200000);
            });
    }

    @Test
    void 기존_공통_직렬화기와_호환되며_타입_헤더에_영향받지_않는다() {
        runner.run(context -> {
            var mapper = context.getBean(JsonMapper.class);
            var producerFactory = new DefaultKafkaProducerFactory<Object, Object>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092"));
            new NotificationKafkaConfiguration().notificationKafkaProducerFactoryCustomizer(mapper)
                .customize(producerFactory);
            var event = new AiSimulationEventFactory().requested(10L, LocalDateTime.of(2026, 11, 6, 10, 5));
            var key = producerFactory.getKeySerializer().serialize("requested", 10L);
            var value = producerFactory.getValueSerializer().serialize("requested", event);
            assertThat(key).isEqualTo(new LongSerializer().serialize("requested", 10L));

            var factory = context.getBean("aiSimulationGenerationKafkaListenerContainerFactory",
                ConcurrentKafkaListenerContainerFactory.class);
            var configs = factory.getConsumerFactory().getConfigurationProperties();
            var keyDeserializer = factory.getConsumerFactory().getKeyDeserializer();
            var valueDeserializer = factory.getConsumerFactory().getValueDeserializer();
            keyDeserializer.configure(configs, true);
            valueDeserializer.configure(configs, false);
            try {
                var headers = new RecordHeaders();
                headers.add("__TypeId__", "java.lang.String".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                assertThat(keyDeserializer.deserialize("requested", headers, key)).isEqualTo(10L);
                assertThat(valueDeserializer.deserialize("requested", headers, value))
                    .isInstanceOf(AiSimulationRequestedEvent.class).isEqualTo(event);
                assertThat(keyDeserializer.deserialize("requested", new RecordHeaders(), new byte[]{1})).isNull();
            } finally {
                keyDeserializer.close();
                valueDeserializer.close();
                producerFactory.destroy();
            }
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiSimulationKafkaProperties.class)
    static class PropertiesConfiguration {}
}
