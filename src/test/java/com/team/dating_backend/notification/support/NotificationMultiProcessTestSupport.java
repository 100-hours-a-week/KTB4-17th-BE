package com.team.dating_backend.notification.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.team.dating_backend.auth.config.JwtProperties;
import com.team.dating_backend.auth.service.JwtService;
import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import com.team.dating_backend.user.enums.UserStatus;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class NotificationMultiProcessTestSupport {

    protected static final String NOTIFICATIONS = "/api/v1/notifications";
    protected static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    protected NotificationIntegrationEnvironment environment;
    protected JdbcTemplate jdbc;
    protected URI first;
    protected URI second;
    protected HttpClient client;
    private final List<NotificationSseProbe> probes = new ArrayList<>();

    @BeforeAll
    void startEnvironment() throws Exception {
        environment = createEnvironment();
        environment.start();
        jdbc = environment.jdbc();
        first = environment.first().address();
        second = environment.second().address();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    protected NotificationIntegrationEnvironment createEnvironment() {
        return new NotificationIntegrationEnvironment();
    }

    protected void restartFirst() throws Exception {
        environment.restartFirst();
        first = environment.first().address();
    }

    @AfterEach
    void closeConnections() throws Exception {
        Exception failure = null;
        for (var probe : probes) {
            try {
                probe.close();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        probes.clear();
        if (failure != null) {
            throw failure;
        }
    }

    @AfterAll
    void stopEnvironment() throws Exception {
        try {
            if (client != null) {
                client.shutdownNow();
            }
        } finally {
            if (environment != null) {
                environment.close();
            }
        }
    }

    protected long user(UserStatus status) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                INSERT INTO users (birth_date, created_at, last_accessed_at, name, updated_at,
                    face_verification_status, gender, persona_onboarding_status, status, withdrawn_at)
                VALUES ('2000-01-01', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), '테스트', UTC_TIMESTAMP(6),
                    'NOT_VERIFIED', 'MALE', 'CONFIRMED', ?, IF(? = 'WITHDRAWN', UTC_TIMESTAMP(6), NULL))
                """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, status.name());
            statement.setString(2, status.name());
            return statement;
        }, key);
        long id = key.getKey().longValue();
        jdbc.update("""
            INSERT INTO profiles (created_at, updated_at, user_id, nickname)
            VALUES (UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, ?)
            """, id, "u" + id);
        return id;
    }

    protected long activeUser() {
        return user(UserStatus.ACTIVE);
    }

    protected String token(long userId) {
        return token(userId, environment.jwtSecret(), 60);
    }

    protected String token(long userId, String secret, long expirationMinutes) {
        var properties = new JwtProperties();
        properties.setSecret(secret);
        properties.setServiceExpirationMinutes(expirationMinutes);
        return new JwtService(properties).createServiceAuthToken(userId);
    }

    protected HttpResponse<String> request(URI server, String method, String path, String token, Object body)
        throws Exception {
        var builder = HttpRequest.newBuilder(server.resolve(path)).timeout(Duration.ofSeconds(10))
            .header("Accept", path.equals(NOTIFICATIONS + "/stream") ? "text/event-stream" : "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(method, body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected JsonNode success(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as("response body=%s", response.body()).isEqualTo(status);
        return JSON.readTree(response.body()).path("data");
    }

    protected void error(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as("response body=%s", response.body()).isEqualTo(status);
        var body = JSON.readTree(response.body());
        assertThat(body.properties()).hasSize(1);
        assertThat(body.path("errorCode").asText()).isEqualTo(code);
    }

    protected JsonNode like(URI server, long sender, long recipient) throws Exception {
        return success(request(server, "POST", "/api/v1/likes", token(sender), Map.of("receiverId", recipient)), 201);
    }

    protected NotificationSseProbe subscribe(URI server, long userId) {
        var probe = NotificationSseProbe.open(client, server, userId, token(userId));
        probes.add(probe);
        return probe;
    }

    protected NotificationEventResponse stored(Expected expected) {
        var result = new ArrayList<NotificationEventResponse>();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var rows = jdbc.query("""
                SELECT id, type, title, body, target_type, target_id, read_at, created_at
                FROM notifications WHERE source_event_id = ? AND user_id = ? AND type = ?
                """, (row, index) -> new NotificationEventResponse(
                row.getLong("id"), row.getString("type"), row.getString("title"), row.getString("body"),
                row.getString("target_type"), row.getObject("target_id", Long.class),
                row.getObject("read_at", LocalDateTime.class), row.getObject("created_at", LocalDateTime.class)),
                expected.sourceEventId(), expected.recipientUserId(), expected.type());
            assertThat(rows).hasSize(1);
            result.clear();
            result.addAll(rows);
        });
        return result.getFirst();
    }

    protected void reconcile(String scenario, List<Expected> expected, NotificationSseProbe... connections)
        throws Exception {
        var rows = new LinkedHashMap<Expected, NotificationEventResponse>();
        expected.forEach(key -> rows.put(key, stored(key)));
        var users = new ArrayList<>(expected.stream().map(Expected::recipientUserId).distinct().toList());
        for (var probe : connections) {
            if (!users.contains(probe.userId())) {
                users.add(probe.userId());
            }
        }
        String placeholders = users.stream().map(user -> "?").collect(Collectors.joining(","));
        var databaseIds = jdbc.queryForList("SELECT id FROM notifications WHERE user_id IN (" + placeholders + ")",
            Long.class, users.toArray());
        Throwable failure = null;
        try {
            assertThat(databaseIds).containsExactlyInAnyOrderElementsOf(rows.values().stream()
                .map(NotificationEventResponse::notificationId).toList());
            environment.awaitProcessed();
            await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                for (var probe : connections) {
                    probe.assertHealthy();
                    var wanted = rows.entrySet().stream()
                        .filter(entry -> entry.getKey().recipientUserId() == probe.userId()).map(Map.Entry::getValue)
                        .toList();
                    assertThat(probe.notifications()).containsExactlyInAnyOrderElementsOf(wanted);
                }
            });
        } catch (Exception | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                writeReconciliation(scenario, expected, rows, databaseIds, connections);
            } catch (Exception | Error reportFailure) {
                if (failure == null) {
                    throw reportFailure;
                }
                failure.addSuppressed(reportFailure);
            }
        }
    }

    private void writeReconciliation(String scenario, List<Expected> expected,
        Map<Expected, NotificationEventResponse> rows, List<Long> databaseIds, NotificationSseProbe[] connections)
        throws Exception {
        var received = new ArrayList<Map<String, Object>>();
        long missing = 0;
        long unexpected = 0;
        long duplicates = 0;
        for (var probe : connections) {
            var wantedIds = rows.entrySet().stream().filter(entry -> entry.getKey().recipientUserId() == probe.userId())
                .map(entry -> entry.getValue().notificationId()).toList();
            var notifications = probe.notifications();
            var frequencies = notifications.stream().collect(Collectors.groupingBy(
                NotificationEventResponse::notificationId, Collectors.counting()));
            missing += wantedIds.stream().filter(id -> !frequencies.containsKey(id)).count();
            unexpected += notifications.stream().filter(event -> !wantedIds.contains(event.notificationId())).count();
            duplicates += frequencies.values().stream().mapToLong(count -> Math.max(0, count - 1)).sum();
            received.add(Map.of("userId", probe.userId(), "server", probe.server().toString(),
                "connectionId", probe.connectionId(), "notificationIdCounts", frequencies, "notifications",
                notifications));
        }
        var wantedDatabaseIds = rows.values().stream().map(NotificationEventResponse::notificationId).toList();
        var mapping = rows.entrySet().stream().map(entry -> Map.of("expected", entry.getKey(),
            "stored", entry.getValue())).toList();
        var result = new LinkedHashMap<String, Object>();
        result.put("expectedAndStored", mapping);
        result.put("connections", received);
        result.put("expectedRows", expected.size());
        result.put("databaseRows", databaseIds.size());
        result.put("databaseIds", databaseIds);
        result.put("missingRows", wantedDatabaseIds.stream().filter(id -> !databaseIds.contains(id)).count());
        result.put("unexpectedRows", databaseIds.stream().filter(id -> !wantedDatabaseIds.contains(id)).count());
        result.put("missingEvents", missing);
        result.put("unexpectedEvents", unexpected);
        result.put("duplicateEvents", duplicates);
        environment.report(scenario, result);
    }

    protected Expected likeNotification(URI server, long sender, long recipient) throws Exception {
        long likeId = like(server, sender, recipient).path("likeId").asLong();
        return new Expected("LIKE_SENT:" + likeId, recipient, "LIKE");
    }

    public record Expected(String sourceEventId, long recipientUserId, String type) {}
}
