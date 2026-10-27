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
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.LongSerializer;
import tools.jackson.databind.json.JsonMapper;

public final class NotificationKafkaProbe implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final Duration API_TIMEOUT = Duration.ofSeconds(5);
    private final Admin admin;
    private final String bootstrapServers;
    private final String creationTopic;
    private final Set<String> topics;
    private final KafkaProducer<Long, byte[]> producer;
    private final KafkaConsumer<Long, byte[]> consumer;
    private final Map<String, List<TopicPartition>> partitions = new HashMap<>();
    private final Map<TopicPartition, Integer> observedLeaders = new HashMap<>();
    private Admin offsetReader;

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

    public record PartitionState(String topic, int partition, Integer leaderNodeId,
        List<Integer> replicas, List<Integer> inSyncReplicas) {}

    public record Topology(Instant observedAt, String clusterId, List<Integer> brokerIds,
        Map<Integer, String> endpoints,
        List<PartitionState> partitions) {}

    public record PartitionProgress(int partition, long endOffset, Long committedOffset, Long lag) {}

    public record GroupProgress(String group, String topic, Instant observedAt,
        List<PartitionProgress> partitions) {}

    public record ObservedHeader(String key, String valueBase64) {}

    public record ObservedRecord(Position position, long timestamp, Long key, String valueBase64,
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
        this.bootstrapServers = bootstrap;
        this.creationTopic = creationTopic;
        this.topics = Set.copyOf(topics);
        consumer = new KafkaConsumer<>(Map.of("bootstrap.servers", bootstrap,
            "key.deserializer", LongDeserializer.class, "value.deserializer", ByteArrayDeserializer.class,
            "enable.auto.commit", false, "allow.auto.create.topics", false, "auto.offset.reset", "none",
            "default.api.timeout.ms", 5000, "request.timeout.ms", 3000));
        try {
            producer = new KafkaProducer<>(Map.of("bootstrap.servers", bootstrap,
                "key.serializer", LongSerializer.class, "value.serializer", ByteArraySerializer.class,
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
        var record = new ProducerRecord<>(creationTopic, partition, request.recipientUserId(),
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

    public Topology topology() throws Exception {
        return topology(topics);
    }

    public Topology topologyWithConsumerOffsets() throws Exception {
        var observedTopics = new java.util.HashSet<>(topics);
        observedTopics.add("__consumer_offsets");
        return topology(observedTopics);
    }

    private Topology topology(Set<String> observedTopics) throws Exception {
        Instant observedAt = Instant.now();
        var cluster = admin.describeCluster();
        var descriptions = admin.describeTopics(observedTopics).allTopicNames();
        String clusterId = awaitResult(cluster.clusterId(), API_TIMEOUT);
        var nodes = awaitResult(cluster.nodes(), API_TIMEOUT);
        var endpoints = new HashMap<Integer, String>();
        nodes.forEach(node -> endpoints.put(node.id(), node.host() + ":" + node.port()));
        var states = new ArrayList<PartitionState>();
        for (var topic : awaitResult(descriptions, API_TIMEOUT).values()) {
            for (var partition : topic.partitions()) {
                var leader = partition.leader();
                states.add(new PartitionState(topic.name(), partition.partition(),
                    leader == null || leader.id() < 0 ? null : leader.id(),
                    partition.replicas().stream().map(node -> node.id()).sorted().toList(),
                    partition.isr().stream().map(node -> node.id()).sorted().toList()));
            }
        }
        states.sort(Comparator.comparing(PartitionState::topic).thenComparingInt(PartitionState::partition));
        var leaders = new HashMap<TopicPartition, Integer>();
        states.stream().filter(state -> topics.contains(state.topic()))
            .forEach(state -> leaders.put(new TopicPartition(state.topic(), state.partition()), state.leaderNodeId()));
        if (!leaders.equals(observedLeaders)) {
            if (offsetReader != null) {
                var previous = offsetReader;
                offsetReader = null;
                previous.close(API_TIMEOUT);
            }
            observedLeaders.clear();
            observedLeaders.putAll(leaders);
        }
        return new Topology(observedAt, clusterId, nodes.stream().map(node -> node.id()).sorted().toList(),
            Map.copyOf(endpoints), List.copyOf(states));
    }

    public GroupProgress progress(String group, String topic) throws Exception {
        assertThat(topics).contains(topic);
        Instant observedAt = Instant.now();
        var end = offsets(partitions(topic), OffsetSpec.latest());
        var committed = awaitResult(admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata(), API_TIMEOUT);
        var progress = end.entrySet().stream().sorted(Comparator.comparingInt(entry -> entry.getKey().partition()))
            .map(entry -> {
                var metadata = committed.get(entry.getKey());
                Long offset = metadata == null ? null : metadata.offset();
                return new PartitionProgress(entry.getKey().partition(), entry.getValue(), offset,
                    offset == null ? null : entry.getValue() - offset);
            }).toList();
        return new GroupProgress(group, topic, observedAt, progress);
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
        var request = assigned.stream().collect(Collectors.toMap(partition -> partition, partition -> specification));
        if (offsetReader == null) {
            offsetReader = Admin.create(Map.of("bootstrap.servers", bootstrapServers,
                "default.api.timeout.ms", 10000, "request.timeout.ms", 3000));
        }
        var result = awaitResult(offsetReader.listOffsets(request, new ListOffsetsOptions().timeoutMs(10000)).all(),
            Duration.ofSeconds(12));
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
        try {
            if (offsetReader != null) {
                offsetReader.close(API_TIMEOUT);
            }
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
