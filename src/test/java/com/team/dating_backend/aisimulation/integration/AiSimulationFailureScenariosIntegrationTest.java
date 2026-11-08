package com.team.dating_backend.aisimulation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import com.team.dating_backend.aisimulation.dto.request.AiSimulationCreateRequest;
import com.team.dating_backend.aisimulation.service.AiSimulationNotificationService;
import com.team.dating_backend.aisimulation.service.AiSimulationService;
import com.team.dating_backend.aisimulation.service.AiSimulationWorker;
import com.team.dating_backend.aisimulation.support.AiSimulationFaultProbe;
import com.team.dating_backend.aisimulation.support.AiSimulationFaultProbe.ControlledAi;
import com.team.dating_backend.recommendation.service.SimulationRecommendationService;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Characterization tests: passing means the current limitation was reproduced,
 * not fixed.
 */
@SpringBootTest(
    classes = AiSimulationFaultProbe.Config.class, properties = {
        "spring.config.name=ai-simulation-fault-test", "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.main.web-application-type=none",
        "app.ai-simulation.async-pool-size=2", "app.ai-simulation.async-shutdown-wait-seconds=1"}
)
@Testcontainers
class AiSimulationFailureScenariosIntegrationTest {
    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private static final Path EVIDENCE = Path.of("build/reports/ai-simulation-faults");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private AiSimulationService service;
    @Autowired
    private AiSimulationWorker worker;
    @Autowired
    private SimulationRecommendationService recommendations;
    @Autowired
    private ControlledAi ai;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ThreadPoolTaskExecutor executor;
    @MockitoSpyBean
    private AiSimulationNotificationService notifications;

    private CountDownLatch delayedNotification;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(EVIDENCE);
        ai.reset();
        for (String table : List.of("notification_request_outbox", "notifications", "ai_simulation_messages",
            "ai_simulation_reports", "ai_simulation_sessions", "user_pair_locks", "recommendation_items",
            "recommendation_batches", "recommendation_passes", "profiles", "users")) {
            jdbc.update("DELETE FROM " + table);
        }
        for (long id : List.of(7L, 8L, 9L)) {
            jdbc.update("""
                INSERT INTO users (id, birth_date, created_at, last_accessed_at, updated_at, name,
                    face_verification_status, gender, persona_onboarding_status, status)
                VALUES (?, '1995-01-01', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), '재현회원',
                    'NOT_VERIFIED', 'MALE', 'CONFIRMED', 'ACTIVE')
                """, id);
            jdbc.update("""
                INSERT INTO profiles (user_id, nickname, created_at, updated_at)
                VALUES (?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, id, "fault-" + id);
        }
    }

    @AfterEach
    void finishWorkers() {
        if (delayedNotification != null) {
            delayedNotification.countDown();
        }
        ai.release();
        idle();
    }

    @Test
    void acceptedQueuedWorkIsNotResubmittedAfterActualProcessKillAndRestart() throws Exception {
        long runningId;
        long queuedId;
        Map<String, Object> beforeKill;
        try (var first = new ProbeProcess("before-kill")) {
            first.start(true);
            runningId = first.command("request 7 8").get("simulationId").asLong();
            await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(first.command("stats").get("aiCalls").asInt()).isEqualTo(1));
            queuedId = first.command("request 7 9").get("simulationId").asLong();
            var stats = first.command("stats");
            assertThat(stats.get("aiCalls").asInt()).isEqualTo(1);
            assertThat(stats.get("queued").asInt()).isEqualTo(1);
            assertThat(state(runningId)).isEqualTo("IN_PROGRESS");
            assertThat(state(queuedId)).isEqualTo("IN_PROGRESS");
            beforeKill = Map.of("runningId", runningId, "queuedId", queuedId,
                "aiCalls", 1, "queued", 1, "requestNotifications", requestedCount(),
                "recommendations", recommendationCount());
            first.kill();
        }
        jdbc.update("UPDATE ai_simulation_sessions SET created_at=UTC_TIMESTAMP(6)-INTERVAL 1 DAY");
        try (var restarted = new ProbeProcess("after-restart")) {
            restarted.start(false);
            assertThat(restarted.command("stats").get("aiCalls").asInt()).isZero();
            assertThat(state(runningId)).isEqualTo("IN_PROGRESS");
            assertThat(state(queuedId)).isEqualTo("IN_PROGRESS");
            assertThat(restarted.command("request 7 9").get("simulationId").asLong()).isEqualTo(queuedId);
            assertThat(restarted.command("stats").get("aiCalls").asInt()).isZero();
            evidence("process-restart", Map.of("beforeKill", beforeKill,
                "afterRestart", "day-old sessions remain IN_PROGRESS, same ID reused, no AI call",
                "forcedExit", true));
        }
    }

    @Test
    void recommendationFailureIsNotRepairedByRepeatingAcceptedRequest() throws Exception {
        rootSql("""
            CREATE TRIGGER reject_simulation_recommendation BEFORE INSERT ON recommendation_items
            FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected recommendation failure'
            """);
        long id;
        try {
            id = service.request(7L, new AiSimulationCreateRequest(8L)).simulationId();
            completed(id);
            assertThat(recommendationCount()).isZero();
            assertThat(requestedCount()).isEqualTo(1);
        } finally {
            rootSql("DROP TRIGGER reject_simulation_recommendation");
        }
        assertThat(service.request(7L, new AiSimulationCreateRequest(8L)).simulationId()).isEqualTo(id);
        assertThat(recommendationCount()).isZero();
        assertThat(ai.callCount()).isEqualTo(1);
        recommendations.add(8L, 7L);
        assertThat(recommendationCount()).isEqualTo(1);
        evidence("recommendation-failure", Map.of("simulation", state(id), "aiCalls", ai.callCount(),
            "recommendationsAfterSameRequest", 0, "recommendationsAfterExplicitRepair", recommendationCount()));
    }

    @Test
    void requestNotificationFailureIsNotRepairedByRepeatingAcceptedRequest() throws Exception {
        jdbc.execute("""
            ALTER TABLE notification_request_outbox ADD CONSTRAINT reject_simulation_requested
            CHECK (type <> 'AI_SIMULATION_REQUESTED')
            """);
        long id;
        try {
            id = service.request(7L, new AiSimulationCreateRequest(8L)).simulationId();
            completed(id);
            assertThat(requestedCount()).isZero();
            assertThat(recommendationCount()).isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE notification_request_outbox DROP CHECK reject_simulation_requested");
        }
        assertThat(service.request(7L, new AiSimulationCreateRequest(8L)).simulationId()).isEqualTo(id);
        assertThat(requestedCount()).isZero();
        assertThat(ai.callCount()).isEqualTo(1);
        notifications.requested(id, 7L, 8L);
        assertThat(requestedCount()).isEqualTo(1);
        evidence("request-notification-failure", Map.of("simulation", state(id), "aiCalls", ai.callCount(),
            "requestsAfterSameRequest", 0, "requestsAfterExplicitRepair", requestedCount()));
    }

    @Test
    void completionNotificationFailureLeavesCompletedResultWithoutAutomaticRepair() throws Exception {
        jdbc.execute("""
            ALTER TABLE notification_request_outbox ADD CONSTRAINT reject_simulation_completed
            CHECK (type <> 'AI_SIMULATION_REPORT_COMPLETED')
            """);
        long id;
        try {
            id = service.request(7L, new AiSimulationCreateRequest(8L)).simulationId();
            completed(id);
            assertThat(completedCount()).isZero();
            assertThat(messageCount()).isEqualTo(20);
        } finally {
            jdbc.execute("ALTER TABLE notification_request_outbox DROP CHECK reject_simulation_completed");
        }
        service.request(7L, new AiSimulationCreateRequest(8L));
        assertThat(completedCount()).isZero();
        notifications.completed(id, 7L, 8L, 7L);
        notifications.completed(id, 7L, 8L, 8L);
        assertThat(completedCount()).isEqualTo(2);
        evidence("completion-notification-failure", Map.of("simulation", state(id), "messages", messageCount(),
            "completionRequestsAfterSameRequest", 0, "completionRequestsAfterExplicitRepair", completedCount()));
    }

    @Test
    void delayedRequestNotificationBlocksApiReturnWhileCompletionCanAlreadyBeRecorded() throws Exception {
        var entered = new CountDownLatch(1);
        delayedNotification = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!delayedNotification.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test notification release timed out");
            }
            return invocation.callRealMethod();
        }).when(notifications).requested(anyLong(), anyLong(), anyLong());
        try (var callers = Executors.newSingleThreadExecutor()) {
            var response = callers.submit(() -> service.request(7L, new AiSimulationCreateRequest(8L)));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(completedCount()).isEqualTo(2));
                assertThat(response.isDone()).isFalse();
                assertThat(requestedCount()).isZero();
                delayedNotification.countDown();
                long id = response.get(5, TimeUnit.SECONDS).simulationId();
                assertThat(requestedCount()).isEqualTo(1);
                assertThat(jdbc.queryForList("SELECT type FROM notification_request_outbox ORDER BY id", String.class))
                    .containsExactly("AI_SIMULATION_REPORT_COMPLETED", "AI_SIMULATION_REPORT_COMPLETED",
                        "AI_SIMULATION_REQUESTED");
                evidence("notification-delay", Map.of("simulation", state(id), "apiBlockedUntilRelease", true,
                    "recordOrder", List.of("completed-A", "completed-B", "requested-B")));
            } finally {
                delayedNotification.countDown();
            }
        }
    }

    @Test
    void duplicateGenerationCanCallAiTwiceAlthoughStoredResultIsUnique() throws Exception {
        ai.block();
        long id = service.request(7L, new AiSimulationCreateRequest(8L)).simulationId();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(ai.callCount()).isEqualTo(1));
        worker.generate(id);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(ai.callCount()).isEqualTo(2));
        ai.release();
        completed(id);
        assertThat(messageCount()).isEqualTo(20);
        assertThat(completedCount()).isEqualTo(2);
        assertThat(recommendationCount()).isEqualTo(1);
        recommendations.add(8L, 7L);
        assertThat(recommendationCount()).isEqualTo(1);
        assertThatThrownBy(() -> notifications.requested(id, 7L, 8L))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(requestedCount()).isEqualTo(1);
        evidence("duplicate-delivery", Map.of("aiCalls", ai.callCount(), "messages", messageCount(),
            "completionRequests", completedCount(), "recommendations", recommendationCount(),
            "duplicateRequestNotification", "unique constraint rejects with exception"));
    }

    private void completed(long id) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(state(id)).isEqualTo("COMPLETED"));
        idle();
    }

    private void idle() {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(executor.getActiveCount()).isZero();
            assertThat(executor.getQueueSize()).isZero();
        });
    }

    private String state(long id) {
        return jdbc.queryForObject("SELECT status FROM ai_simulation_sessions WHERE id=?", String.class, id);
    }

    private int requestedCount() {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM notification_request_outbox WHERE type='AI_SIMULATION_REQUESTED'",
            Integer.class);
    }

    private int completedCount() {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM notification_request_outbox WHERE type='AI_SIMULATION_REPORT_COMPLETED'",
            Integer.class);
    }

    private int recommendationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_items WHERE simulation_requested=true",
            Integer.class);
    }

    private int messageCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ai_simulation_messages", Integer.class);
    }

    private void rootSql(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
            var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void evidence(String name, Map<String, Object> value) throws Exception {
        Files.writeString(EVIDENCE.resolve(name + ".json"),
            JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        System.out.println("FAULT_EVIDENCE " + name + " " + JSON.writeValueAsString(value));
    }

    private static final class ProbeProcess implements AutoCloseable {
        private final Path directory;
        private final Path log;
        private Process process;
        private BufferedWriter input;
        private int replyCount;

        private ProbeProcess(String name) throws Exception {
            directory = Files.createTempDirectory(EVIDENCE, name + "-").toAbsolutePath();
            log = directory.resolve("process.log");
        }

        private void start(boolean blocked) throws Exception {
            var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m", "-Duser.timezone=UTC", "-cp", classpath(), AiSimulationFaultProbe.class.getName(),
                "--spring.config.name=ai-simulation-fault-test", "--spring.main.banner-mode=off",
                "--spring.datasource.url=" + MYSQL.getJdbcUrl(), "--spring.datasource.username=" + MYSQL.getUsername(),
                "--spring.datasource.password=" + MYSQL.getPassword(), "--spring.flyway.enabled=true",
                "--spring.jpa.hibernate.ddl-auto=validate", "--logging.level.root=WARN",
                "--app.ai-simulation.async-pool-size=1", "--app.ai-simulation.async-shutdown-wait-seconds=0",
                "--probe.block-ai=" + blocked));
            var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile());
            builder.environment().clear();
            builder.environment().put("LANG", "en_US.UTF-8");
            process = builder.start();
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            await().atMost(Duration.ofSeconds(45)).failFast(() -> !process.isAlive()).untilAsserted(
                () -> assertThat(Files.readString(log)).as("child startup log: %s", log).contains("PROBE_READY"));
        }

        private JsonNode command(String command) throws Exception {
            input.write(command);
            input.newLine();
            input.flush();
            int expected = ++replyCount;
            await().atMost(Duration.ofSeconds(15)).failFast(() -> !process.isAlive())
                .untilAsserted(() -> assertThat(replies()).hasSize(expected));
            return JSON.readTree(replies().get(expected - 1).substring("PROBE_REPLY ".length()));
        }

        private List<String> replies() throws Exception {
            return Files.readAllLines(log).stream().filter(line -> line.startsWith("PROBE_REPLY ")).toList();
        }

        private void kill() throws Exception {
            process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isNotZero();
        }

        @Override
        public void close() throws Exception {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            }
            if (input != null) {
                input.close();
            }
        }

        private static String classpath() throws Exception {
            var paths = new LinkedHashSet<String>();
            for (ClassLoader loader = AiSimulationFaultProbe.class.getClassLoader(); loader != null; loader = loader
                .getParent()) {
                if (loader instanceof URLClassLoader urls) {
                    for (var url : urls.getURLs()) {
                        if (url.getProtocol().equals("file")) {
                            paths.add(Path.of(url.toURI()).toAbsolutePath().toString());
                        }
                    }
                }
            }
            Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .map(path -> Path.of(path).toAbsolutePath().toString()).forEach(paths::add);
            return String.join(java.io.File.pathSeparator, paths);
        }
    }
}
