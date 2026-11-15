package com.team.dating_backend.common.kafka.config;

import com.team.dating_backend.common.kafka.KafkaRetryComponentFactory;
import com.team.dating_backend.common.kafka.KafkaRetryPolicy;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonDelegatingErrorHandler;
import org.springframework.kafka.retrytopic.RetryTopicComponentFactory;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationSupport;
import org.springframework.util.backoff.FixedBackOff;

@EnableKafka
@Configuration(proxyBeanMethods = false)
public class KafkaRetryInfrastructureConfiguration extends RetryTopicConfigurationSupport {

    private final ObjectProvider<KafkaRetryPolicy> policies;

    public KafkaRetryInfrastructureConfiguration(ObjectProvider<KafkaRetryPolicy> policies) {
        this.policies = policies;
    }

    @Override
    protected RetryTopicComponentFactory createComponentFactory() {
        return new KafkaRetryComponentFactory(() -> policies.orderedStream().toList());
    }

    @Override
    protected void configureBlockingRetries(BlockingRetriesConfigurer blockingRetries) {
        blockingRetries.backOff(new FixedBackOff(0L, 0L));
    }

    @Override
    protected void manageNonBlockingFatalExceptions(List<Class<? extends Throwable>> fatalExceptions) {
        policies.orderedStream().flatMap(policy -> policy.fatalExceptions().stream())
            .forEach(fatalExceptions::add);
    }

    @Override
    protected void configureCustomizers(CustomizersConfigurer customizers) {
        customizers.customizeErrorHandler(errorHandler -> errorHandler.setAckAfterHandle(true));
        customizers.customizeListenerContainer(container -> {
            var errorHandler = new CommonDelegatingErrorHandler(container.getCommonErrorHandler());
            var stoppingErrorHandler = new CommonContainerStoppingErrorHandler();
            errorHandler.setCauseChainTraversing(true);
            errorHandler.setErrorHandlers(Map.of(
                InterruptedException.class, stoppingErrorHandler,
                Error.class, stoppingErrorHandler));
            container.setCommonErrorHandler(errorHandler);
        });
    }
}
