package com.team.dating_backend.notification.sse;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.config.NotificationKafkaProperties;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.stereotype.Component;

@Component
public class NotificationDeliveryReadinessTracker {

    private final String deliveryTopic;
    private final Set<TopicPartition> expectedPartitions = new HashSet<>();
    private final Map<Consumer<?, ?>, AssignmentState> assignments = new IdentityHashMap<>();
    private long generation;

    public NotificationDeliveryReadinessTracker(NotificationKafkaProperties properties) {
        String topic = properties == null ? null : properties.getDeliveryTopic();
        if (topic == null || topic.isBlank()) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        this.deliveryTopic = topic;
    }

    public synchronized Assignment beginAssignment(Consumer<?, ?> consumer) {
        requireConsumer(consumer);
        Assignment assignment = new Assignment(++generation);
        assignments.put(consumer, new AssignmentState(assignment, Set.of(), false, false));
        return assignment;
    }

    public synchronized boolean describeAssignment(
        Consumer<?, ?> consumer,
        Assignment assignment,
        Collection<TopicPartition> topicPartitions,
        Collection<TopicPartition> assignedPartitions) {
        requireConsumer(consumer);
        if (!currentAssignment(consumer, assignment)) {
            return false;
        }
        try {
            requirePartitions(topicPartitions);
            requirePartitions(assignedPartitions);
            if (topicPartitions.isEmpty() || !topicPartitions.containsAll(assignedPartitions)) {
                throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }
        } catch (RuntimeException exception) {
            assignments.remove(consumer);
            throw exception;
        }

        expectedPartitions.addAll(topicPartitions);
        assignments.put(consumer, new AssignmentState(assignment, Set.copyOf(assignedPartitions), true, false));
        return true;
    }

    public synchronized boolean confirmPositions(
        Consumer<?, ?> consumer,
        Assignment assignment,
        Map<TopicPartition, Long> positions) {
        requireConsumer(consumer);
        AssignmentState state = assignments.get(consumer);
        if (state == null || state.assignment() != assignment) {
            return false;
        }
        if (!state.partitionsDescribed() || positions == null
            || !state.partitions().equals(positions.keySet())
            || positions.values().stream().anyMatch(position -> position == null || position < 0)) {
            assignments.remove(consumer);
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }

        assignments.put(consumer, new AssignmentState(assignment, state.partitions(), true, true));
        return true;
    }

    public synchronized void invalidate(Consumer<?, ?> consumer) {
        requireConsumer(consumer);
        assignments.remove(consumer);
    }

    public synchronized void invalidate(Consumer<?, ?> consumer, Assignment assignment) {
        requireConsumer(consumer);
        if (currentAssignment(consumer, assignment)) {
            assignments.remove(consumer);
        }
    }

    public synchronized void invalidateAll() {
        assignments.clear();
    }

    public synchronized boolean isReady() {
        if (expectedPartitions.isEmpty()) {
            return false;
        }

        Set<TopicPartition> positioned = new HashSet<>();
        for (AssignmentState state : assignments.values()) {
            if (!state.positionsConfirmed()) {
                return false;
            }
            for (TopicPartition partition : state.partitions()) {
                if (!positioned.add(partition)) {
                    return false;
                }
            }
        }
        return positioned.equals(expectedPartitions);
    }

    private void requireConsumer(Consumer<?, ?> consumer) {
        if (consumer == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private boolean currentAssignment(Consumer<?, ?> consumer, Assignment assignment) {
        AssignmentState state = assignments.get(consumer);
        return state != null && state.assignment() == assignment;
    }

    private void requirePartitions(Collection<TopicPartition> partitions) {
        if (partitions == null || partitions.stream().anyMatch(partition -> partition == null
            || !deliveryTopic.equals(partition.topic()) || partition.partition() < 0)) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    public record Assignment(long generation) {}

    private record AssignmentState(
        Assignment assignment,
        Set<TopicPartition> partitions,
        boolean partitionsDescribed,
        boolean positionsConfirmed) {}
}
