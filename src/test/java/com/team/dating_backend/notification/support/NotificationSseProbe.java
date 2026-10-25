package com.team.dating_backend.notification.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.team.dating_backend.notification.dto.response.NotificationEventResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.json.JsonMapper;

public final class NotificationSseProbe implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private final long userId;
    private final URI server;
    private final InputStream stream;
    private final List<NotificationEventResponse> notifications = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Thread reader;
    private volatile String connectionId;
    private volatile boolean closed;

    private NotificationSseProbe(long userId, URI server, InputStream stream) {
        this.userId = userId;
        this.server = server;
        this.stream = stream;
        this.reader = Thread.ofVirtual().name("notification-sse-probe").start(this::read);
    }

    public static NotificationSseProbe open(HttpClient client, URI server, long userId, String token) {
        var result = new AtomicReference<NotificationSseProbe>();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var request = HttpRequest.newBuilder(server.resolve("/api/v1/notifications/stream"))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token)
                .header("Accept", "text/event-stream").GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                try (var input = response.body()) {
                    var body = JSON.readTree(input);
                    assertThat(response.statusCode()).isEqualTo(503);
                    assertThat(body.path("errorCode").asText()).isEqualTo("NOTIFICATION_STREAM_NOT_READY");
                }
                assertThat(response.statusCode()).isEqualTo(200);
            }
            assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("text/event-stream");
            result.set(new NotificationSseProbe(userId, server, response.body()));
        });
        var probe = result.get();
        try {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                probe.assertHealthy();
                assertThat(probe.connectionId).isNotBlank();
            });
            return probe;
        } catch (RuntimeException | Error error) {
            try {
                probe.close();
            } catch (Exception closeFailure) {
                error.addSuppressed(closeFailure);
            }
            throw error;
        }
    }

    private void read() {
        try (var input = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String event = "";
            var data = new StringBuilder();
            String line;
            while ((line = input.readLine()) != null) {
                if (line.isEmpty()) {
                    if (event.equals("connected")) {
                        connectionId = JSON.readTree(data.toString()).path("connectionId").asText();
                    } else if (event.equals("notification")) {
                        var payload = JSON.readTree(data.toString());
                        assertThat(payload.properties()).hasSize(8);
                        notifications.add(JSON.treeToValue(payload, NotificationEventResponse.class));
                    }
                    event = "";
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).stripLeading();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).stripLeading());
                }
            }
            if (!closed) {
                failure.compareAndSet(null, new IOException());
            }
        } catch (Throwable error) {
            if (!closed) {
                failure.compareAndSet(null, error);
            }
        }
    }

    public void assertHealthy() {
        assertThat(failure.get()).isNull();
    }

    public long userId() {
        return userId;
    }

    public String connectionId() {
        return connectionId;
    }

    public URI server() {
        return server;
    }

    public List<NotificationEventResponse> notifications() {
        return List.copyOf(notifications);
    }

    @Override
    public void close() throws IOException, InterruptedException {
        closed = true;
        try {
            stream.close();
        } finally {
            assertThat(reader.join(Duration.ofSeconds(5))).isTrue();
        }
    }
}
