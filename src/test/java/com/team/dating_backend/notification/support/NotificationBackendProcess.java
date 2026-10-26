package com.team.dating_backend.notification.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

public final class NotificationBackendProcess implements AutoCloseable {

    private static final Pattern PORT = Pattern.compile("Tomcat started on port (\\d+)");
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private final String name;
    private final Path directory;
    private final List<Generation> history = new ArrayList<>();
    private Map<String, String> settings;
    private Path generationDirectory;
    private Process process;
    private URI address;
    private int generation;
    private String deliveryGroupPrefix;
    private Instant startedAt;
    private Instant exitObservedAt;
    private Termination termination;

    public enum Termination {
        RUNNING, KILLED, GRACEFUL, FORCED_AFTER_TIMEOUT, EXITED
    }

    public record Generation(int number, long pid, URI address, String configuration, String log,
        String deliveryGroupPrefix, Instant startedAt, Instant exitObservedAt, Integer exitCode,
        Termination termination, boolean alive) {}

    public NotificationBackendProcess(String name, Path directory) {
        this.name = name;
        this.directory = directory.resolve(name);
    }

    public void start(Map<String, String> settings) throws IOException {
        assertThat(isAlive()).isFalse();
        writeHistory();
        var snapshot = Map.copyOf(settings);
        assertThat(snapshot).containsKeys("DELIVERY_PREFIX", "JWT_SECRET");
        int nextGeneration = generation + 1;
        Path nextDirectory = directory.resolve("generation-%03d".formatted(nextGeneration));
        String nextDeliveryPrefix = snapshot.get("DELIVERY_PREFIX") + "-g" + nextGeneration;
        var renderedSettings = new HashMap<>(snapshot);
        renderedSettings.put("DELIVERY_PREFIX", nextDeliveryPrefix);
        Files.createDirectories(nextDirectory);
        String template;
        try (var input = NotificationBackendProcess.class.getResourceAsStream("/notification-multiprocess.yaml")) {
            if (input == null) {
                throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }
            template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (var entry : renderedSettings.entrySet()) {
            template = template.replace("@" + entry.getKey() + "@", entry.getValue());
        }
        assertThat(template).doesNotContainPattern("@[A-Z_]+@");
        Path configuration = nextDirectory.resolve("application.yaml");
        Files.writeString(configuration, template);
        Path jar = Path.of(System.getProperty("notification.test.application-jar"));
        assertThat(jar).isRegularFile();
        var command = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xms64m", "-Xmx256m", "-XX:MaxMetaspaceSize=192m", "-Duser.timezone=UTC"));
        if (snapshot.containsKey("HTTPS_PROXY_PORT")) {
            command.add("-Dhttps.proxyHost=127.0.0.1");
            command.add("-Dhttps.proxyPort=" + snapshot.get("HTTPS_PROXY_PORT"));
        }
        command.addAll(List.of(
            "-jar", jar.toString(), "--spring.config.location=" + configuration.toUri(),
            "--spring.main.banner-mode=off"));
        var builder = new ProcessBuilder(command);
        builder.directory(nextDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(nextDirectory.resolve("backend.log").toFile());
        builder.environment().clear();
        builder.environment().putAll(Map.of("LANG", "en_US.UTF-8", "TZ", "UTC",
            "AWS_ACCESS_KEY_ID", "notification-integration", "AWS_SECRET_ACCESS_KEY", snapshot.get("JWT_SECRET"),
            "AWS_EC2_METADATA_DISABLED", "true"));
        process = builder.start();
        this.settings = snapshot;
        generation = nextGeneration;
        generationDirectory = nextDirectory;
        deliveryGroupPrefix = nextDeliveryPrefix;
        address = null;
        startedAt = Instant.now();
        exitObservedAt = null;
        termination = Termination.RUNNING;
        writeHistory();
        await().atMost(Duration.ofSeconds(90)).failFast(() -> !process.isAlive()).untilAsserted(() -> {
            assertThat(process.isAlive()).as("%s startup, log=%s", name, log()).isTrue();
            var matcher = PORT.matcher(Files.readString(log()));
            assertThat(matcher.find()).as("%s HTTP port, log=%s", name, log()).isTrue();
            address = URI.create("http://127.0.0.1:" + matcher.group(1));
        });
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> {
                var request = HttpRequest.newBuilder(address.resolve("/actuator/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
                assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
            });
        }
        writeHistory();
    }

    public void kill() throws IOException, InterruptedException {
        assertThat(isAlive()).isTrue();
        termination = Termination.KILLED;
        process.destroyForcibly();
        boolean exited = waitForExit(5);
        writeHistory();
        assertThat(exited).isTrue();
    }

    public void restart() throws IOException {
        assertThat(process).isNotNull();
        assertThat(isAlive()).isFalse();
        start(settings);
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    public int generation() {
        return generation;
    }

    public String deliveryGroupPrefix() {
        return deliveryGroupPrefix;
    }

    public List<Generation> history() {
        if (process != null) {
            boolean alive = process.isAlive();
            if (!alive && exitObservedAt == null) {
                exitObservedAt = Instant.now();
                if (termination == Termination.RUNNING) {
                    termination = Termination.EXITED;
                }
            }
            var current = new Generation(generation, process.pid(), address,
                generationDirectory.resolve("application.yaml").toString(), log().toString(),
                deliveryGroupPrefix, startedAt, exitObservedAt, alive ? null : process.exitValue(), termination, alive);
            if (history.size() < generation) {
                history.add(current);
            } else {
                history.set(generation - 1, current);
            }
        }
        return List.copyOf(history);
    }

    private void writeHistory() throws IOException {
        if (process != null) {
            Files.writeString(directory.resolve("process-history.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(history()));
        }
    }

    private boolean waitForExit(long seconds) throws InterruptedException {
        try {
            return process.waitFor(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        }
    }

    public URI address() {
        return address;
    }

    public long pid() {
        return process.pid();
    }

    public Path log() {
        return generationDirectory.resolve("backend.log");
    }

    @Override
    public void close() throws IOException, InterruptedException {
        if (isAlive()) {
            termination = Termination.GRACEFUL;
            process.destroy();
            if (!waitForExit(15)) {
                termination = Termination.FORCED_AFTER_TIMEOUT;
                process.destroyForcibly();
                boolean exited = waitForExit(5);
                writeHistory();
                assertThat(exited).isTrue();
            }
        }
        writeHistory();
    }
}
