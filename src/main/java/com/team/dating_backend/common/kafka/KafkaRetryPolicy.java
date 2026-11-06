package com.team.dating_backend.common.kafka;

import java.util.Set;
import org.springframework.kafka.retrytopic.DeadLetterPublishingRecovererFactory;

public interface KafkaRetryPolicy {

    Set<String> listenerIds();

    long publishResultTimeoutMs();

    default Set<Class<? extends Exception>> fatalExceptions() {
        return Set.of();
    }

    default void customizeRecovererFactory(DeadLetterPublishingRecovererFactory factory) {}
}
