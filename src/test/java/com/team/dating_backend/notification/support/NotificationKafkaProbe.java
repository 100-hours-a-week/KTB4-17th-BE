package com.team.dating_backend.notification.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import tools.jackson.databind.json.JsonMapper;

public final class NotificationKafkaProbe implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final Duration API_TIMEOUT = Duration.ofSeconds(5);
    private final Admin admin;
    private final String creationTopic;
    private final Set<String> topics;
    private final KafkaProducer<String, byte[]> producer;
    private final KafkaConsumer<String, byte[]> consumer;
    private final Map<String, List<TopicPartition>> partitions = new HashMap<>();

    public record Position(String topic, int partition, long offset) {
        public TopicPartition topicPartition() {
            return new TopicPartition(topic, partition);
        }
    }

    public record InputState(String group, Position input, Long committedOffset) {
        public boolean completed() {
            return committedOffset != null && committedOffset > input.offset();
        }
    }

    public record ObservedHeader(String key, String valueBase64) {}

    public record ObservedRecord(Position position, long timestamp, String key, String valueBase64,
        List<ObservedHeader> headers) {
        public ObservedRecord {
            headers = List.copyOf(headers);
        }

        public byte[] value() {
            return valueBase64 == null ? null : Base64.getDecoder().decode(valueBase64);
        }
    }

    public record TopicSnapshot(String topic, Instant observedAt, Map<Integer, Long> beginningOffsets,
        Map<Integer, Long> endOffsets, List<ObservedRecord> records) {
        public TopicSnapshot {
            beginningOffsets = Map.copyOf(beginningOffsets);
            endOffsets = Map.copyOf(endOffsets);
            records = List.copyOf(records);
        }
    }

    public NotificationKafkaProbe(Admin admin, String bootstrap, String creationTopic, Set<String> topics) {
        this.admin = admin;
        this.creationTopic = creationTopic;
        this.topics = Set.copyOf(topics);
        consumer = new KafkaConsumer<>(Map.of("bootstrap.servers", bootstrap,
            "key.deserializer", StringDeserializer.class, "value.deserializer", ByteArrayDeserializer.class,
            "enable.auto.commit", false, "allow.auto.create.topics", false, "auto.offset.reset", "none",
            "default.api.timeout.ms", 5000, "request.timeout.ms", 3000));
        try {
            producer = new KafkaProducer<>(Map.of("bootstrap.servers", bootstrap,
                "key.serializer", StringSerializer.class, "value.serializer", ByteArraySerializer.class,
                "acks", "all", "enable.idempotence", true, "max.block.ms", 5000,
                "request.timeout.ms", 3000, "delivery.timeout.ms", 10000));
        } catch (RuntimeException | Error failure) {
            try {
                consumer.close(API_TIMEOUT);
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public Position publish(NotificationCreateRequested request)
        throws InterruptedException, ExecutionException, TimeoutException {
        return publish(request, null);
    }

    public Position publish(NotificationCreateRequested request, Integer partition)
        throws InterruptedException, ExecutionException, TimeoutException {
        var record = new ProducerRecord<>(creationTopic, partition, request.recipientUserId().toString(),
            JSON.writeValueAsBytes(request));
        var metadata = awaitResult(producer.send(record), Duration.ofSeconds(12));
        return new Position(metadata.topic(), metadata.partition(), metadata.offset());
    }

    public InputState inputState(String group, Position input) throws Exception {
        assertThat(topics).contains(input.topic());
        var committed = awaitResult(admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata(), API_TIMEOUT)
            .get(input.topicPartition());
        return new InputState(group, input, committed == null ? null : committed.offset());
    }

    public TopicSnapshot snapshot(String topic) throws Exception {
        var assigned = partitions(topic);
        var beginning = offsets(assigned, OffsetSpec.earliest());
        var end = offsets(assigned, OffsetSpec.latest());
        Instant observedAt = Instant.now();
        consumer.assign(assigned);
        beginning.forEach((partition, offset) -> consumer.seek(partition, offset));
        var records = new ArrayList<ObservedRecord>();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!reachedEnd(end)) {
            assertThat(System.nanoTime()).as("topic=%s, snapshotEnd=%s", topic, end).isLessThan(deadline);
            for (var record : consumer.poll(Duration.ofMillis(100))) {
                var partition = new TopicPartition(record.topic(), record.partition());
                if (record.offset() >= beginning.get(partition) && record.offset() < end.get(partition)) {
                    var headers = StreamSupport.stream(record.headers().spliterator(), false)
                        .map(header -> new ObservedHeader(header.key(), base64(header.value()))).toList();
                    records.add(new ObservedRecord(new Position(record.topic(), record.partition(), record.offset()),
                        record.timestamp(), record.key(), base64(record.value()), headers));
                }
            }
        }
        records.sort(Comparator.comparingInt((ObservedRecord record) -> record.position().partition())
            .thenComparingLong(record -> record.position().offset()));
        return new TopicSnapshot(topic, observedAt, partitionOffsets(beginning), partitionOffsets(end), records);
    }

    private boolean reachedEnd(Map<TopicPartition, Long> end) {
        return end.entrySet().stream()
            .allMatch(entry -> consumer.position(entry.getKey(), API_TIMEOUT) >= entry.getValue());
    }

    private List<TopicPartition> partitions(String topic) throws Exception {
        assertThat(topics).contains(topic);
        if (!partitions.containsKey(topic)) {
            var description = awaitResult(admin.describeTopics(List.of(topic)).allTopicNames(), API_TIMEOUT).get(topic);
            partitions.put(topic, description.partitions().stream()
                .map(partition -> new TopicPartition(topic, partition.partition())).toList());
        }
        return partitions.get(topic);
    }

    private Map<TopicPartition, Long> offsets(List<TopicPartition> assigned, OffsetSpec specification)
        throws Exception {
        var result = awaitResult(admin.listOffsets(assigned.stream().collect(Collectors.toMap(partition -> partition,
            partition -> specification))).all(), API_TIMEOUT);
        return result.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().offset()));
    }

    private static Map<Integer, Long> partitionOffsets(Map<TopicPartition, Long> offsets) {
        return offsets.entrySet().stream()
            .collect(Collectors.toMap(entry -> entry.getKey().partition(), Map.Entry::getValue));
    }

    private static String base64(byte[] value) {
        return value == null ? null : Base64.getEncoder().encodeToString(value);
    }

    private static <T> T awaitResult(Future<T> future, Duration timeout)
        throws InterruptedException, ExecutionException, TimeoutException {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        }
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        try {
            consumer.close(API_TIMEOUT);
        } catch (RuntimeException exception) {
            failure = exception;
        }
        try {
            producer.close(API_TIMEOUT);
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
