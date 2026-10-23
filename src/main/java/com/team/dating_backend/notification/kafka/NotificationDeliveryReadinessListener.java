package com.team.dating_backend.notification.kafka;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.config.NotificationDeliveryProperties;
import com.team.dating_backend.notification.config.NotificationKafkaProperties;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.sse.NotificationDeliveryReadinessTracker;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.event.ConsumerStoppingEvent;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

@Component
public class NotificationDeliveryReadinessListener {

    private static final String DELIVERY_LISTENER_ID = "notificationDeliveryConsumer";
    private static final long MAX_PREPARATION_TIMEOUT_MS = 30_000;

    private final NotificationDeliveryReadinessTracker tracker;
    private final String deliveryTopic;
    private final long preparationTimeoutNanos;

    public NotificationDeliveryReadinessListener(
        NotificationDeliveryReadinessTracker tracker,
        NotificationKafkaProperties kafkaProperties,
        NotificationDeliveryProperties deliveryProperties) {
        if (tracker == null || kafkaProperties == null || kafkaProperties.getDeliveryTopic() == null
            || kafkaProperties.getDeliveryTopic().isBlank() || deliveryProperties == null
            || deliveryProperties.getMaxPollIntervalMs() <= 0) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        this.tracker = tracker;
        this.deliveryTopic = kafkaProperties.getDeliveryTopic();
        this.preparationTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(
            Math.min(MAX_PREPARATION_TIMEOUT_MS, deliveryProperties.getMaxPollIntervalMs()));
    }

    public ConsumerAwareRebalanceListener createRebalanceListener() {
        var listener = this;
        return new ConsumerAwareRebalanceListener() {
            @Override
            public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                listener.onPartitionsAssigned(consumer, partitions);
            }

            @Override
            public void onPartitionsRevokedBeforeCommit(Consumer<?, ?> consumer,
                Collection<TopicPartition> partitions) {
                listener.onPartitionsRevokedBeforeCommit(consumer, partitions);
            }

            @Override
            public void onPartitionsLost(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                listener.onPartitionsLost(consumer, partitions);
            }
        };
    }

    public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        var assignment = tracker.beginAssignment(consumer);
        long startedAt = System.nanoTime();
        try {
            requireNotInterrupted();
            var metadata = consumer.partitionsFor(deliveryTopic, remaining(startedAt));
            if (metadata == null || metadata.isEmpty() || metadata.stream().anyMatch(partition -> partition == null)) {
                throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }
            Set<TopicPartition> topicPartitions = metadata.stream()
                .map(partition -> new TopicPartition(partition.topic(), partition.partition()))
                .collect(Collectors.toSet());
            Set<TopicPartition> assigned = consumer.assignment();
            if (partitions == null || assigned == null || !assigned.containsAll(partitions)) {
                throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }
            if (!tracker.describeAssignment(consumer, assignment, topicPartitions, assigned)) {
                return;
            }
            var positions = new HashMap<TopicPartition, Long>();
            for (TopicPartition partition : assigned) {
                requireNotInterrupted();
                positions.put(partition, consumer.position(partition, remaining(startedAt)));
            }
            requireNotInterrupted();
            tracker.confirmPositions(consumer, assignment, positions);
        } catch (InterruptException exception) {
            tracker.invalidate(consumer, assignment);
            Thread.currentThread().interrupt();
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, exception);
        } catch (RuntimeException exception) {
            tracker.invalidate(consumer, assignment);
            throw exception instanceof NotificationBusinessException
                ? exception
                : new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, exception);
        } catch (Error error) {
            tracker.invalidate(consumer, assignment);
            throw error;
        }
    }

    public void onPartitionsRevokedBeforeCommit(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        tracker.invalidate(consumer);
    }

    public void onPartitionsLost(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        tracker.invalidate(consumer);
    }

    @EventListener
    public void onConsumerStopping(ConsumerStoppingEvent event) {
        MessageListenerContainer container = event.getContainer(MessageListenerContainer.class);
        if (DELIVERY_LISTENER_ID.equals(container.getListenerId())) {
            tracker.invalidate(event.getConsumer());
        }
    }

    private Duration remaining(long startedAt) {
        long remainingNanos = preparationTimeoutNanos - (System.nanoTime() - startedAt);
        if (remainingNanos <= 0) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, new TimeoutException());
        }
        return Duration.ofNanos(remainingNanos);
    }

    private void requireNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
