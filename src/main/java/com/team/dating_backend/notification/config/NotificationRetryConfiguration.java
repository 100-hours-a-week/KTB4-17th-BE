package com.team.dating_backend.notification.config;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.exception.NotificationInvalidCreateRequestException;
import com.team.dating_backend.notification.exception.NotificationRetryableCreationException;
import com.team.dating_backend.notification.kafka.NotificationRecoveryReporter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonDelegatingErrorHandler;
import org.springframework.kafka.retrytopic.DeadLetterPublishingRecovererFactory;
import org.springframework.kafka.retrytopic.RetryTopicConfiguration;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationBuilder;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationSupport;
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;
import org.springframework.util.backoff.FixedBackOff;

@EnableKafka
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class NotificationRetryConfiguration extends RetryTopicConfigurationSupport {

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
            .exponentialBackoff(retryProperties.getInitialDelayMs(), retryProperties.getMultiplier(),
                retryProperties.getMaxDelayMs())
            .concurrency(retryProperties.getConcurrency())
            .retryOn(NotificationRetryableCreationException.class)
            .traversingCauses()
            .retryTopicSuffix("-retry")
            .dltSuffix("-dlt")
            .suffixTopicsWithIndexValues()
            .sameIntervalTopicReuseStrategy(SameIntervalTopicReuseStrategy.MULTIPLE_TOPICS)
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
    protected void configureBlockingRetries(BlockingRetriesConfigurer blockingRetries) {
        blockingRetries.backOff(new FixedBackOff(0L, 0L));
    }

    @Override
    protected void manageNonBlockingFatalExceptions(List<Class<? extends Throwable>> nonBlockingFatalExceptions) {
        nonBlockingFatalExceptions.add(NotificationInvalidCreateRequestException.class);
    }

    @Override
    protected Consumer<DeadLetterPublishingRecovererFactory> configureDeadLetterPublishingContainerFactory() {
        return factory -> {
            factory.setDeadLetterPublisherCreator(recoveryReporter::createRecoverer);
            factory.neverLogListenerException();
        };
    }

    @Override
    protected void configureCustomizers(CustomizersConfigurer customizersConfigurer) {
        customizersConfigurer.customizeDeadLetterPublishingRecoverer(recoverer -> {
            recoverer.setFailIfSendResultIsError(true);
            recoverer.setWaitForSendResultTimeout(Duration.ofMillis(retryProperties.getPublishResultTimeoutMs()));
            recoverer.setThrowIfNoDestinationReturned(true);
            recoverer.setLogRecoveryRecord(false);
        });
        customizersConfigurer.customizeErrorHandler(errorHandler -> errorHandler.setAckAfterHandle(true));
        customizersConfigurer.customizeListenerContainer(container -> {
            var stoppingErrorHandler = new CommonContainerStoppingErrorHandler();
            var errorHandler = new CommonDelegatingErrorHandler(container.getCommonErrorHandler());
            errorHandler.setCauseChainTraversing(true);
            errorHandler.setErrorHandlers(Map.of(
                InterruptedException.class, stoppingErrorHandler,
                Error.class, stoppingErrorHandler));
            container.setCommonErrorHandler(errorHandler);
        });
    }
}
