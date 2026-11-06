package com.team.dating_backend.aisimulation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.team.dating_backend.aisimulation.dto.event.AiSimulationCompletedEvent;
import com.team.dating_backend.aisimulation.dto.event.AiSimulationRequestedEvent;
import com.team.dating_backend.aisimulation.exception.AiSimulationRetryableProcessingException;
import com.team.dating_backend.aisimulation.kafka.AiSimulationKafkaListeners;
import com.team.dating_backend.common.config.SchedulingConfig;
import com.team.dating_backend.common.kafka.KafkaRetryComponentFactory;
import com.team.dating_backend.common.kafka.KafkaRetryPolicy;
import java.util.List;
import java.util.Set;
import org.springframework.kafka.retrytopic.DeadLetterPublishingRecovererFactory;
import com.team.dating_backend.notification.config.NotificationCreationProperties;
import com.team.dating_backend.notification.config.NotificationKafkaConfiguration;
import com.team.dating_backend.notification.config.NotificationPublishProperties;
import com.team.dating_backend.notification.config.NotificationRetryConfiguration;
import com.team.dating_backend.notification.config.NotificationRetryProperties;
import com.team.dating_backend.notification.kafka.NotificationCreationFailureClassifier;
import com.team.dating_backend.notification.kafka.NotificationRecoveryTestConfiguration;
import java.util.HashSet;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.retrytopic.DestinationTopicResolver;
import org.springframework.kafka.retrytopic.DestinationTopic;
import org.springframework.kafka.retrytopic.RetryTopicConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

class AiSimulationRetryConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, KafkaAutoConfiguration.class,
            TaskSchedulingAutoConfiguration.class))
        .withUserConfiguration(SchedulingConfig.class, NotificationKafkaConfiguration.class,
            NotificationRetryConfiguration.class, NotificationRecoveryTestConfiguration.class,
            AiSimulationKafkaConfiguration.class, AiSimulationRetryConfiguration.class,
            AiSimulationKafkaTopicConfiguration.class, PropertiesConfiguration.class, TestListeners.class)
        .withBean(NotificationCreationFailureClassifier.class, NotificationCreationFailureClassifier::new)
        .withPropertyValues(
            "spring.kafka.admin.auto-create=false",
            "app.notification.kafka.create-requested-topic=local-notification-create-requested-v1",
            "app.notification.kafka.delivery-topic=local-notification-delivery-v1");

    @Test
    void 같은_원본_토픽의_다섯_업무가_각자의_Retry와_DLT를_사용한다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var registry = context.getBean(KafkaListenerEndpointRegistry.class);
            var resolver = context.getBean(DestinationTopicResolver.class);
            var topicNames = new HashSet<String>();
            for (var definition : AiSimulationKafkaListeners.definitions()) {
                var listener = AnnotatedElementUtils.findMergedAnnotation(definition, KafkaListener.class);
                var retry = AnnotatedElementUtils.findMergedAnnotation(definition, RetryableTopic.class);
                String original = context.getEnvironment().resolveRequiredPlaceholders(listener.topics()[0]);
                String group = context.getEnvironment().resolveRequiredPlaceholders(listener.groupId());
                var main = (ConcurrentMessageListenerContainer<?, ?>) registry.getListenerContainer(listener.id());
                var retrier = (ConcurrentMessageListenerContainer<?, ?>) registry
                    .getListenerContainer(listener.id() + retry.retryTopicSuffix());
                var dlt = registry.getListenerContainer(listener.id() + retry.dltTopicSuffix());
                assertThat(main.getConcurrency()).isEqualTo(2);
                assertThat(retrier.getConcurrency()).isEqualTo(1);
                assertThat(retrier.getContainerProperties().getGroupId()).isEqualTo(group + retry.retryTopicSuffix());
                assertThat(main.isRunning()).isFalse();
                assertThat(retrier.isAutoStartup()).isFalse();
                assertThat(dlt.isAutoStartup()).isFalse();
                assertThat(dlt.isRunning()).isFalse();
                assertThat(retrier.getContainerProperties().getTopics())
                    .containsExactly(original + retry.retryTopicSuffix());
                var destination = resolver.getDestinationTopicByName(listener.id(),
                    original + retry.retryTopicSuffix());
                assertThat(destination.getDestinationDelay()).isEqualTo(5000L);
                assertThat(destination.getDestinationPartitions()).isEqualTo(4);
                var failure = new AiSimulationRetryableProcessingException(new IllegalStateException());
                assertThat(destination.shouldRetryOn(3, failure)).isTrue();
                assertThat(destination.shouldRetryOn(4, failure)).isFalse();
                assertThat(destination.shouldRetryOn(1, new IllegalArgumentException())).isFalse();
                assertThat(destination.getKafkaOperations())
                    .isSameAs(context.getBean("kafkaTemplate"));
                assertThat(topicNames.add(original + retry.retryTopicSuffix())).isTrue();
                assertThat(topicNames.add(original + retry.dltTopicSuffix())).isTrue();
            }
            assertThat(topicNames).hasSize(10);
            assertThat(context).hasSingleBean(ProducerFactory.class).hasSingleBean(KafkaTemplate.class);
            assertThat(context.getBean(KafkaTemplate.class)).isSameAs(context.getBean("kafkaTemplate"));
            var notification = context.getBean("notificationRetryTopicConfiguration", RetryTopicConfiguration.class);
            assertThat(notification.getDestinationTopicProperties()).extracting(destination -> destination.suffix())
                .containsExactly("", "-retry", "-dlt");
        });
    }

    @Test
    void 재시도_횟수와_간격과_동시성을_외부_설정으로_변경한다() {
        runner.withPropertyValues("app.ai-simulation.kafka.retry.max-attempts=5",
            "app.ai-simulation.kafka.retry.delay-ms=1000", "app.ai-simulation.kafka.retry.concurrency=2")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var retry = (ConcurrentMessageListenerContainer<?, ?>) context
                    .getBean(KafkaListenerEndpointRegistry.class)
                    .getListenerContainer(AiSimulationKafkaListeners.GENERATION + "-generation-retry");
                assertThat(retry.getConcurrency()).isEqualTo(2);
                var destination = context.getBean(DestinationTopicResolver.class).getDestinationTopicByName(
                    AiSimulationKafkaListeners.GENERATION, "local-ai-simulation-requested-generation-retry");
                assertThat(destination.getDestinationDelay()).isEqualTo(1000L);
                var failure = new AiSimulationRetryableProcessingException(new IllegalStateException());
                assertThat(destination.shouldRetryOn(4, failure)).isTrue();
                assertThat(destination.shouldRetryOn(5, failure)).isFalse();
            });
    }

    @Test
    void 모든_토픽은_동일한_파티션수와_내구성_설정을_사용한다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var topics = context.getBean("aiSimulationKafkaTopics", KafkaAdmin.NewTopics.class);
            Collection<NewTopic> definitions = ReflectionTestUtils.invokeMethod(topics, "getNewTopics");
            assertThat(definitions).hasSize(12).allSatisfy(topic -> {
                assertThat(topic.numPartitions()).isEqualTo(4);
                assertThat(topic.replicationFactor()).isEqualTo((short) 3);
                assertThat(topic.configs()).containsEntry("min.insync.replicas", "2")
                    .containsEntry("cleanup.policy", "delete").doesNotContainKey("retention.ms");
            });
            assertThat(context.getBean(KafkaProperties.class).getAdmin().isAutoCreate()).isFalse();
        });
    }

    @Test
    void 잘못된_파생_토픽명과_내구성_설정을_거절한다() {
        runner.withPropertyValues("app.ai-simulation.kafka.requested-topic=" + "a".repeat(240))
            .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.ai-simulation.kafka.min-in-sync-replicas=4")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 시뮬레이션_재전달은_알림_리포터와_분리하고_손상된_키도_직렬화한다() {
        var notificationRecoverer = mock(DeadLetterPublishingRecoverer.class);
        var notificationPolicy = new KafkaRetryPolicy() {
            @Override
            public Set<String> listenerIds() {
                return Set.of("notificationCreationConsumer");
            }

            @Override
            public long publishResultTimeoutMs() {
                return 15000L;
            }

            @Override
            public void customizeRecovererFactory(DeadLetterPublishingRecovererFactory factory) {
                factory.setDeadLetterPublisherCreator((template, destination) -> notificationRecoverer);
            }
        };
        var factory = new KafkaRetryComponentFactory(() -> List.of(notificationPolicy,
            new AiSimulationRetryConfiguration(new AiSimulationKafkaProperties())))
            .deadLetterPublishingRecovererFactory(mock(DestinationTopicResolver.class));
        assertThat(factory.create("notificationCreationConsumer")).isSameAs(notificationRecoverer);
        assertThat(factory.create(AiSimulationKafkaListeners.GENERATION)).isNotSameAs(notificationRecoverer);
        runner.run(context -> {
            var producer = context.getBean(DefaultKafkaProducerFactory.class);
            byte[] bytes = new byte[]{1, 2, 3};
            assertThat(producer.getKeySerializer().serialize("retry", new RecordHeaders(), bytes)).isEqualTo(bytes);
            assertThat(producer.getValueSerializer().serialize("retry", new RecordHeaders(), bytes)).isEqualTo(bytes);
        });
    }

    @Test
    void Retry_전달_실패를_상위로_전달하고_원본_파티션을_유지한다() {
        var resolver = mock(DestinationTopicResolver.class);
        var destination = mock(DestinationTopic.class);
        KafkaOperations<Object, Object> operations = mock(KafkaOperations.class);
        doReturn(operations).when(destination).getKafkaOperations();
        when(destination.getDestinationName()).thenReturn("local-ai-simulation-requested-generation-retry");
        when(destination.getDestinationDelay()).thenReturn(5000L);
        when(resolver.resolveDestinationTopic(eq(AiSimulationKafkaListeners.GENERATION), anyString(), anyInt(),
            any(Exception.class), anyLong())).thenReturn(destination);
        when(resolver.getDestinationTopicByName(eq(AiSimulationKafkaListeners.GENERATION), anyString()))
            .thenReturn(destination);
        when(operations.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException()));
        var recoverer = new KafkaRetryComponentFactory(() -> List.of(
            new AiSimulationRetryConfiguration(new AiSimulationKafkaProperties())))
            .deadLetterPublishingRecovererFactory(resolver).create(AiSimulationKafkaListeners.GENERATION);
        recoverer.setVerifyPartition(false);
        var record = new ConsumerRecord<>("local-ai-simulation-requested", 3, 10L, 20L, "event");

        assertThatThrownBy(() -> recoverer.accept(record, new IllegalStateException()))
            .isInstanceOf(KafkaException.class);
        verify(operations).send(argThat((ProducerRecord<Object, Object> output) -> output.partition().equals(3)
            && output.topic().equals("local-ai-simulation-requested-generation-retry")));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(
        {AiSimulationKafkaProperties.class, NotificationCreationProperties.class,
            NotificationPublishProperties.class, NotificationRetryProperties.class}
    )
    static class PropertiesConfiguration {}

    static class TestListeners {

        @AiSimulationKafkaListeners.Generation
        void generate(AiSimulationRequestedEvent event) {}

        @AiSimulationKafkaListeners.RequestNotification
        void requestNotification(AiSimulationRequestedEvent event) {}

        @AiSimulationKafkaListeners.Recommendation
        void recommend(AiSimulationRequestedEvent event) {}

        @AiSimulationKafkaListeners.RequesterNotification
        void requesterNotification(AiSimulationCompletedEvent event) {}

        @AiSimulationKafkaListeners.TargetNotification
        void targetNotification(AiSimulationCompletedEvent event) {}
    }
}
