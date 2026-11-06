package com.team.dating_backend.notification.config;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.common.kafka.KafkaRetryPolicy;
import com.team.dating_backend.common.kafka.config.KafkaRetryInfrastructureConfiguration;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.exception.NotificationInvalidCreateRequestException;
import com.team.dating_backend.notification.exception.NotificationRetryableCreationException;
import com.team.dating_backend.notification.kafka.NotificationRecoveryReporter;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.retrytopic.DeadLetterPublishingRecovererFactory;
import org.springframework.kafka.retrytopic.RetryTopicConfiguration;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationBuilder;
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;

@Configuration(proxyBeanMethods = false)
@Import(KafkaRetryInfrastructureConfiguration.class)
@RequiredArgsConstructor
public class NotificationRetryConfiguration implements KafkaRetryPolicy {

    private final NotificationRetryProperties retryProperties;
    private final NotificationRecoveryReporter recoveryReporter;

    @Bean
    public RetryTopicConfiguration notificationRetryTopicConfiguration(
        KafkaTemplate<Object, Object> kafkaTemplate,
        NotificationKafkaProperties kafkaProperties) {
        RetryTopicConfiguration configuration = RetryTopicConfigurationBuilder.newInstance()
            .includeTopic(kafkaProperties.getCreateRequestedTopic())
            .listenerFactory("notificationCreateRequestedKafkaListenerContainerFactory")
            .maxAttempts(retryProperties.getMaxAttempts())
            .fixedBackOff(retryProperties.getDelayMs())
            .concurrency(retryProperties.getConcurrency())
            .retryOn(NotificationRetryableCreationException.class)
            .traversingCauses()
            .retryTopicSuffix("-retry")
            .dltSuffix("-dlt")
            .sameIntervalTopicReuseStrategy(SameIntervalTopicReuseStrategy.SINGLE_TOPIC)
            .autoStartDltHandler(false)
            .doNotRetryOnDltFailure()
            .doNotAutoCreateRetryTopics()
            .create(kafkaTemplate);

        for (var destination : configuration.getDestinationTopicProperties()) {
            String topic = kafkaProperties.getCreateRequestedTopic() + destination.suffix();
            if (topic.length() > 249 || topic.equals(kafkaProperties.getDeliveryTopic())) {
                throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }
        }
        return configuration;
    }

    @Override
    public Set<String> listenerIds() {
        return Set.of("notificationCreationConsumer");
    }

    @Override
    public long publishResultTimeoutMs() {
        return retryProperties.getPublishResultTimeoutMs();
    }

    @Override
    public Set<Class<? extends Exception>> fatalExceptions() {
        return Set.of(NotificationInvalidCreateRequestException.class);
    }

    @Override
    public void customizeRecovererFactory(DeadLetterPublishingRecovererFactory factory) {
        factory.setDeadLetterPublisherCreator(recoveryReporter::createRecoverer);
        factory.neverLogListenerException();
    }
}
