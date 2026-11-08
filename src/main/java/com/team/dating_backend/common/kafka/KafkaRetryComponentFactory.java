package com.team.dating_backend.common.kafka;

import com.team.dating_backend.common.enums.CommonErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.retrytopic.DeadLetterPublishingRecovererFactory;
import org.springframework.kafka.retrytopic.DestinationTopicResolver;
import org.springframework.kafka.retrytopic.RetryTopicComponentFactory;

public class KafkaRetryComponentFactory extends RetryTopicComponentFactory {

    private static final long DEFAULT_PUBLISH_RESULT_TIMEOUT_MS = 15000L;

    private final Supplier<List<KafkaRetryPolicy>> policies;

    public KafkaRetryComponentFactory(Supplier<List<KafkaRetryPolicy>> policies) {
        this.policies = policies;
    }

    @Override
    public DeadLetterPublishingRecovererFactory deadLetterPublishingRecovererFactory(
        DestinationTopicResolver destinationTopicResolver) {
        return new DeadLetterPublishingRecovererFactory(destinationTopicResolver) {
            @Override
            public DeadLetterPublishingRecoverer create(String mainListenerId) {
                var matches = policies.get().stream()
                    .filter(policy -> policy.listenerIds().contains(mainListenerId)).toList();
                if (matches.size() > 1) {
                    throw new BeanInitializationException(CommonErrorCode.INTERNAL_SERVER_ERROR.name());
                }
                var factory = new DeadLetterPublishingRecovererFactory(destinationTopicResolver);
                factory.setPartitionResolver((record, topic) -> record.partition());
                long timeout = matches.isEmpty()
                    ? DEFAULT_PUBLISH_RESULT_TIMEOUT_MS
                    : matches.getFirst().publishResultTimeoutMs();
                factory.setDeadLetterPublishingRecovererCustomizer(recoverer -> {
                    recoverer.setFailIfSendResultIsError(true);
                    recoverer.setWaitForSendResultTimeout(Duration.ofMillis(timeout));
                    recoverer.setThrowIfNoDestinationReturned(true);
                    recoverer.setLogRecoveryRecord(false);
                });
                if (!matches.isEmpty()) {
                    var policy = matches.getFirst();
                    policy.fatalExceptions().forEach(factory::addNotRetryableException);
                    policy.customizeRecovererFactory(factory);
                }
                return factory.create(mainListenerId);
            }
        };
    }
}
