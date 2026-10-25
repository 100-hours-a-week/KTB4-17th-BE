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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class NotificationBackendProcess implements AutoCloseable {

    private static final Pattern PORT = Pattern.compile("Tomcat started on port (\\d+)");
    private final String name;
    private final Path directory;
    private Process process;
    private URI address;

    public NotificationBackendProcess(String name, Path directory) {
        this.name = name;
        this.directory = directory.resolve(name);
    }

    public void start(Map<String, String> settings) throws IOException {
        Files.createDirectories(directory);
        String template;
        try (var input = NotificationBackendProcess.class.getResourceAsStream("/notification-multiprocess.yaml")) {
            if (input == null) {
                throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }
            template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (var entry : settings.entrySet()) {
            template = template.replace("@" + entry.getKey() + "@", entry.getValue());
        }
        assertThat(template).doesNotContainPattern("@[A-Z_]+@");
        Path configuration = directory.resolve("application.yaml");
        Files.writeString(configuration, template);
        Path jar = Path.of(System.getProperty("notification.test.application-jar"));
        assertThat(jar).isRegularFile();
        var builder = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xms64m", "-Xmx256m", "-XX:MaxMetaspaceSize=192m", "-Duser.timezone=UTC",
            "-jar", jar.toString(), "--spring.config.location=" + configuration.toUri(),
            "--spring.main.banner-mode=off");
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(log().toFile());
        builder.environment().clear();
        builder.environment().putAll(Map.of("LANG", "en_US.UTF-8", "TZ", "UTC",
            "AWS_ACCESS_KEY_ID", "notification-integration", "AWS_SECRET_ACCESS_KEY", settings.get("JWT_SECRET"),
            "AWS_EC2_METADATA_DISABLED", "true"));
        process = builder.start();
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
    }

    public URI address() {
        return address;
    }

    public long pid() {
        return process.pid();
    }

    public Path log() {
        return directory.resolve("backend.log");
    }

    @Override
    public void close() throws InterruptedException {
        if (process != null && process.isAlive()) {
            process.destroy();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }
}
