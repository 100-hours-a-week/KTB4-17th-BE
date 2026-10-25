package com.team.dating_backend.notification.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import com.team.dating_backend.aisimulation.AiSimulationTestFixtures;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.PersonaBrief;
import com.team.dating_backend.aisimulation.dto.ai.AiSimulationAiPayloads.SimulationResponse;
import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.config.ConfigResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Network;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

public final class NotificationIntegrationEnvironment implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private final String runId = "pr24-" + UUID.randomUUID();
    private final Path directory = Path.of(System.getProperty("notification.test.output-directory")).resolve(runId);
    private final Deque<AutoCloseable> resources = new ArrayDeque<>();
    private final List<KafkaContainer> brokers = new ArrayList<>();
    private final String jwtSecret = Base64.getEncoder().encodeToString(randomKey());
    private final AtomicInteger aiRequests = new AtomicInteger();
    private Admin admin;
    private JdbcTemplate jdbc;
    private NotificationBackendProcess first;
    private NotificationBackendProcess second;

    public void start() throws Exception {
        Files.createDirectories(directory);
        var network = resource(Network.newNetwork());
        var mysql = resource(new MySQLContainer("mysql:8.4")
            .withDatabaseName("notification").withUsername("notification")
            .withPassword(UUID.randomUUID().toString()));
        String clusterId = Uuid.randomUuid().toString();
        String voters = "1@kafka-1:9094,2@kafka-2:9094,3@kafka-3:9094";
        for (int index = 1; index <= 3; index++) {
            String alias = "kafka-" + index;
            var broker = resource(new KafkaContainer("apache/kafka:4.3.1")
                .withNetwork(network).withNetworkAliases(alias)
                .withCreateContainerCmdModifier(command -> command.withHostName(alias))
                .withEnv("CLUSTER_ID", clusterId)
                .withEnv("KAFKA_NODE_ID", Integer.toString(index))
                .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", voters)
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                .withEnv("KAFKA_DEFAULT_REPLICATION_FACTOR", "3")
                .withEnv("KAFKA_MIN_INSYNC_REPLICAS", "2")
                .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "3")
                .withEnv("KAFKA_OFFSETS_TOPIC_NUM_PARTITIONS", "3")
                .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "3")
                .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "2")
                .withEnv("KAFKA_UNCLEAN_LEADER_ELECTION_ENABLE", "false")
                .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx256m")
                .withStartupTimeout(Duration.ofSeconds(90)));
            brokers.add(broker);
        }
        Startables.deepStart(Stream.concat(Stream.of(mysql), brokers.stream())).join();
        String bootstrap = brokers.stream().map(KafkaContainer::getBootstrapServers).collect(Collectors.joining(","));
        admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
            AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10000,
            AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000));
        resource(() -> admin.close(Duration.ofSeconds(5)));
        assertThat(admin.describeCluster().nodes().get(10, TimeUnit.SECONDS)).hasSize(3);
        var topics = Stream.of(creationTopic(), retryTopic(), dltTopic(), deliveryTopic())
            .map(name -> new NewTopic(name, 2, (short) 3).configs(name.equals(dltTopic())
                ? Map.of("min.insync.replicas", "2", "retention.ms", "1209600000", "retention.bytes", "-1")
                : Map.of("min.insync.replicas", "2")))
            .toList();
        admin.createTopics(topics).all().get(20, TimeUnit.SECONDS);
        var configurations = admin.describeConfigs(topics.stream()
            .map(topic -> new ConfigResource(ConfigResource.Type.TOPIC, topic.name())).toList())
            .all().get(10, TimeUnit.SECONDS);
        assertThat(configurations.values())
            .allSatisfy(config -> assertThat(config.get("min.insync.replicas").value()).isEqualTo("2"));
        var dltConfiguration = configurations.get(new ConfigResource(ConfigResource.Type.TOPIC, dltTopic()));
        assertThat(dltConfiguration.get("retention.ms").value()).isEqualTo("1209600000");
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> {
            var descriptions = admin.describeTopics(topics.stream().map(NewTopic::name).toList())
                .allTopicNames().get(10, TimeUnit.SECONDS);
            assertThat(descriptions.values()).allSatisfy(topic -> {
                assertThat(topic.partitions()).hasSize(2).allSatisfy(partition -> {
                    assertThat(partition.replicas()).hasSize(3);
                    assertThat(partition.isr()).hasSize(3);
                });
            });
        });
        String aiUrl = startAiServer();
        var dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        var settings = new HashMap<>(Map.of(
            "DB_URL", mysql.getJdbcUrl(), "DB_USERNAME", mysql.getUsername(), "DB_PASSWORD", mysql.getPassword(),
            "BOOTSTRAP_SERVERS", bootstrap, "JWT_SECRET", jwtSecret, "AI_URL", aiUrl,
            "CREATION_TOPIC", creationTopic(), "DELIVERY_TOPIC", deliveryTopic(), "CREATION_GROUP", creationGroup()));
        first = resource(new NotificationBackendProcess("a", directory));
        settings.put("PROCESS", "a");
        settings.put("DELIVERY_PREFIX", runId + "-sse-a");
        first.start(settings);
        second = resource(new NotificationBackendProcess("b", directory));
        settings.put("PROCESS", "b");
        settings.put("DELIVERY_PREFIX", runId + "-sse-b");
        second.start(settings);
        assertThat(first.pid()).isNotEqualTo(second.pid()).isNotEqualTo(ProcessHandle.current().pid());
        assertThat(second.pid()).isNotEqualTo(ProcessHandle.current().pid());
        assertGroupsReady();
        report("environment", Map.of("runId", runId, "clusterId", clusterId,
            "brokers", bootstrap, "replicationFactor", 3, "minInSyncReplicas", 2,
            "creationGroup", creationGroup(), "deliveryGroups", deliveryGroups(),
            "backends", List.of(processDescription(first), processDescription(second))));
    }

    private String startAiServer() throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = resource(Executors.newVirtualThreadPerTaskExecutor());
        server.setExecutor(executor);
        resource(() -> server.stop(0));
        server.createContext("/", exchange -> {
            try (exchange) {
                if (!exchange.getRequestMethod().equals("POST")
                    || !exchange.getRequestURI().getPath().equals("/ai/api/v1/simulation")) {
                    byte[] error = JSON.writeValueAsBytes(Map.of("errorCode", "INTERNAL_SERVER_ERROR"));
                    exchange.sendResponseHeaders(404, error.length);
                    exchange.getResponseBody().write(error);
                    return;
                }
                var request = JSON.readTree(exchange.getRequestBody());
                var fixture = AiSimulationTestFixtures.aiResponse();
                var me = new PersonaBrief("persona-a", request.path("me_user_id").asText(), "나", 1, "차분한 사람", 90);
                var partner = new PersonaBrief("persona-b", request.path("partner_user_id").asText(), "상대", 1,
                    "따뜻한 사람", 85);
                var response = new SimulationResponse(fixture.simulationId(), me, partner, fixture.turns(),
                    fixture.transcript(), fixture.report(), fixture.createdAt());
                byte[] body = JSON.writeValueAsBytes(response);
                aiRequests.incrementAndGet();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void assertGroupsReady() {
        await().atMost(Duration.ofSeconds(45)).ignoreExceptions().untilAsserted(() -> {
            var creation = admin.describeConsumerGroups(List.of(creationGroup())).all().get(10, TimeUnit.SECONDS)
                .get(creationGroup());
            assertThat(creation.members()).hasSize(2)
                .allSatisfy(member -> assertThat(member.assignment().topicPartitions()).hasSize(1));
            assertThat(creation.members().stream().flatMap(member -> member.assignment().topicPartitions().stream()))
                .containsExactlyInAnyOrder(new TopicPartition(creationTopic(), 0),
                    new TopicPartition(creationTopic(), 1));
            var groups = deliveryGroups();
            assertThat(groups).hasSize(2);
            var deliveries = admin.describeConsumerGroups(groups).all().get(10, TimeUnit.SECONDS);
            assertThat(deliveries.values()).allSatisfy(group -> {
                assertThat(group.members()).hasSize(1);
                assertThat(group.members().iterator().next().assignment().topicPartitions())
                    .containsExactlyInAnyOrder(new TopicPartition(deliveryTopic(), 0),
                        new TopicPartition(deliveryTopic(), 1));
            });
        });
    }

    public void awaitProcessed() {
        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> {
            assertCommitted(creationGroup(), creationTopic());
            for (String group : deliveryGroups()) {
                assertCommitted(group, deliveryTopic());
            }
            assertThat(endOffsets(retryTopic()).values()).containsOnly(0L);
            assertThat(endOffsets(dltTopic()).values()).containsOnly(0L);
        });
    }

    private void assertCommitted(String group, String topic) throws Exception {
        var committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
        for (var entry : endOffsets(topic).entrySet()) {
            long offset = committed.containsKey(entry.getKey()) ? committed.get(entry.getKey()).offset() : 0;
            assertThat(offset).as("group=%s, partition=%s", group, entry.getKey()).isEqualTo(entry.getValue());
        }
    }

    private Map<TopicPartition, Long> endOffsets(String topic) throws Exception {
        var result = admin.listOffsets(Map.of(new TopicPartition(topic, 0), OffsetSpec.latest(),
            new TopicPartition(topic, 1), OffsetSpec.latest())).all().get(10, TimeUnit.SECONDS);
        return result.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().offset()));
    }

    private List<String> deliveryGroups() throws Exception {
        return admin.listGroups().all().get(10, TimeUnit.SECONDS).stream()
            .map(group -> group.groupId()).filter(group -> group.startsWith(runId + "-sse-")).sorted().toList();
    }

    public void report(String name, Object content) throws IOException {
        Files.writeString(directory.resolve(name + ".json"),
            JSON.writerWithDefaultPrettyPrinter().writeValueAsString(content));
    }

    private Map<String, Object> processDescription(NotificationBackendProcess backend) {
        return Map.of("pid", backend.pid(), "address", backend.address().toString(), "log", backend.log().toString());
    }

    private <T extends AutoCloseable> T resource(T resource) {
        resources.addFirst(resource);
        return resource;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public NotificationBackendProcess first() {
        return first;
    }

    public NotificationBackendProcess second() {
        return second;
    }

    public String jwtSecret() {
        return jwtSecret;
    }

    public int aiRequests() {
        return aiRequests.get();
    }

    public String creationTopic() {
        return runId + "-create";
    }

    public String retryTopic() {
        return creationTopic() + "-retry";
    }

    public String dltTopic() {
        return creationTopic() + "-dlt";
    }

    public String deliveryTopic() {
        return runId + "-delivery";
    }

    private String creationGroup() {
        return runId + "-creation";
    }

    private static byte[] randomKey() {
        var key = new byte[64];
        new java.security.SecureRandom().nextBytes(key);
        return key;
    }

    @Override
    public void close() throws Exception {
        Exception failure = null;
        for (int index = 0; index < brokers.size(); index++) {
            var broker = brokers.get(index);
            if (broker.isRunning()) {
                try {
                    String logs = broker.getLogs();
                    Files.writeString(directory.resolve("kafka-" + (index + 1) + ".log"),
                        logs.substring(Math.max(0, logs.length() - 512000)));
                } catch (Exception exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
        }
        while (!resources.isEmpty()) {
            try {
                resources.removeFirst().close();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, failure);
        }
    }
}
