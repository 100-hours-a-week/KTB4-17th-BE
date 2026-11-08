package com.team.dating_backend.aisimulation.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.team.dating_backend.aisimulation.config.AiSimulationAlertProperties;
import com.team.dating_backend.aisimulation.config.AiSimulationKafkaProperties;
import com.team.dating_backend.aisimulation.dto.event.AiSimulationRequestedEvent;
import com.team.dating_backend.aisimulation.dto.event.AiSimulationCompletedEvent;
import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.enums.AiSimulationFailureCode;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import com.team.dating_backend.common.alert.DiscordWebhookSender;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.retrytopic.RetryTopicHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class AiSimulationFailureReporterTest {

    private final AiSimulationKafkaProperties kafka = new AiSimulationKafkaProperties();
    private final AiSimulationAlertProperties properties = new AiSimulationAlertProperties();
    private final MockClock clock = new MockClock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
    private final DiscordWebhookSender sender = mock(DiscordWebhookSender.class);
    private final Exception failure = new ListenerExecutionFailedException("private-message", "generation-group",
        new AiSimulationBusinessException(AiSimulationErrorCode.AI_SERVER_UNAVAILABLE));

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setWebhookUrl("https://discord.com/api/webhooks/123/test-token");
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"generation", "request-notification", "recommendation", "requester-notification",
            "target-notification"}
    )
    void 업무별_DLT_확정을_동일한_전송기로_통보하고_Kafka_위치와_오류를_남긴다(String task) {
        var fields = ArgumentCaptor.forClass(Map.class);
        reporter(Runnable::run).report(record(), failure, destination(task, "dlt"), metadata(task), null);

        verify(sender).send(eq(properties.getWebhookUrl()), eq("AI 시뮬레이션 처리 실패"), fields.capture());
        assertThat(fields.getValue()).containsEntry("errorCode", AiSimulationFailureCode.DLT_RECORDED)
            .containsEntry("simulationId", 7L).containsEntry("eventId", "ai-simulation:7:requested")
            .containsEntry("task", task.replace('-', '_').toUpperCase(java.util.Locale.ROOT))
            .containsEntry("consumerGroup", "generation-group").containsEntry("inputOffset", 42L)
            .containsEntry("destinationOffset", 99L).containsEntry("attempt", 4)
            .containsEntry("processingErrorCode", AiSimulationErrorCode.AI_SERVER_UNAVAILABLE);
        assertThat(fields.getValue().toString()).doesNotContain("private-message", "test-token");
    }

    @Test
    void 통보는_실행기에_제출하고_전송이_완료될_때까지_복구_호출자를_기다리게_하지_않는다() {
        var queued = new ArrayList<Runnable>();
        reporter(queued::add).report(record(), failure, destination("generation", "dlt"), metadata("generation"), null);

        verifyNoInteractions(sender);
        assertThat(queued).hasSize(1);
        queued.getFirst().run();
        verify(sender).send(anyString(), anyString(), any());
    }

    @Test
    void 비활성화해도_실패_지표는_기록하고_발송을_제출하지_않는다() {
        properties.setEnabled(false);
        var executor = mock(Executor.class);
        reporter(executor).report(record(), failure, destination("generation", "dlt"), metadata("generation"), null);

        verifyNoInteractions(executor, sender);
        assertThat(registry.get("ai.simulation.failures").counter().count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void 인계_실패와_최종_상태_저장_실패는_구분해서_기록하고_Discord를_보내지_않는다(int scenario) {
        var code = scenario == 0
            ? AiSimulationFailureCode.RETRY_PUBLISH_FAILED
            : scenario == 1
                ? AiSimulationFailureCode.DLT_PUBLISH_FAILED
                : scenario == 2
                    ? AiSimulationFailureCode.FINAL_STATE_UPDATE_FAILED
                    : AiSimulationFailureCode.RECOVERY_FAILED;
        reporter(Runnable::run).report(record(), failure,
            scenario == 3 ? null : destination("generation", scenario == 0 ? "retry" : "dlt"),
            scenario == 2 ? metadata("generation") : null, new IllegalStateException("private-failure"));

        verifyNoInteractions(sender);
        assertThat(registry.get("ai.simulation.failures").tag("error_code", code.name()).counter().count())
            .isEqualTo(1);
    }

    @Test
    void Retry_정상_전달은_최종_실패로_보고하지_않는다() {
        reporter(Runnable::run).report(record(), failure, destination("generation", "retry"), metadata("generation"),
            null);

        verifyNoInteractions(sender);
        assertThat(registry.find("ai.simulation.failures").counter()).isNull();
    }

    @Test
    void 완료_이벤트_발행_실패는_생성_실패와_다른_업무로_표시한다() {
        reporter(Runnable::run).report(record(),
            new AiSimulationBusinessException(AiSimulationErrorCode.SIMULATION_EVENT_PUBLISH_FAILED),
            destination("generation", "dlt"), metadata("generation"), null);

        verify(sender).send(anyString(), anyString(), org.mockito.ArgumentMatchers.argThat(
            fields -> "COMPLETION_PUBLISH".equals(fields.get("task"))));
    }

    @Test
    void 발송_실패는_재전송하지_않고_횟수_제한에_포함하며_시간_구간이_지나면_새_통보를_허용한다() {
        properties.setMaxMessagesPerWindow(1);
        doThrow(new IllegalStateException("private-token")).when(sender).send(anyString(), anyString(), any());
        var reporter = reporter(Runnable::run);
        for (int i = 0; i < 2; i++) {
            assertThatCode(() -> reporter.report(record(), failure, destination("generation", "dlt"),
                metadata("generation"), null)).doesNotThrowAnyException();
        }
        verify(sender).send(anyString(), anyString(), any());
        assertThat(registry.get("ai.simulation.alert.failures").tag("error_code", "ALERT_WEBHOOK_FAILED")
            .counter().count()).isEqualTo(1);
        assertThat(registry.get("ai.simulation.alert.suppressed").counter().count()).isEqualTo(1);
        clock.add(Duration.ofMinutes(1));
        reporter.report(record(), failure, destination("generation", "dlt"), metadata("generation"), null);
        verify(sender, times(2)).send(anyString(), anyString(), any());
    }

    @Test
    void 실행기_제출_거부는_호출자에게_전파하지_않는다() {
        assertThatCode(() -> reporter(command -> {
            throw new RejectedExecutionException();
        })
            .report(record(), failure, destination("generation", "dlt"), metadata("generation"), null))
            .doesNotThrowAnyException();
        verifyNoInteractions(sender);
        assertThat(registry.get("ai.simulation.alert.failures").tag("error_code", "ALERT_SUBMISSION_REJECTED")
            .counter().count()).isEqualTo(1);
    }

    private AiSimulationFailureReporter reporter(Executor executor) {
        return new AiSimulationFailureReporter(properties, kafka, executor, sender, registry);
    }

    @Test
    void 공통_Sender로_실제_HTTP_요청_본문을_만들고_메시지_생성을_확인한다() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
            properties.getWebhookUrl() + "?wait=true"))
            .andExpect(request -> {
                String content = JsonMapper.builder().build()
                    .readTree(((MockClientHttpRequest) request).getBodyAsString()).path("content").stringValue();
                assertThat(content).startsWith("@everyone AI 시뮬레이션 처리 실패\n")
                    .contains("simulationId: 7", "task: GENERATION", "errorCode: DLT_RECORDED", "destinationOffset: 99")
                    .doesNotContain("private-message", "test-token");
            }).andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                "{\"id\":\"123\"}", MediaType.APPLICATION_JSON));
        new AiSimulationFailureReporter(properties, kafka, Runnable::run,
            new DiscordWebhookSender(builder.build()), registry)
            .report(record(), failure, destination("generation", "dlt"), metadata("generation"), null);

        server.verify();
        assertThat(registry.get("ai.simulation.alert.sent").counter().count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void 완료_사건과_식별_불가능한_입력을_본문_노출_없이_보고한다(int scenario) {
        Object value = scenario == 0
            ? new AiSimulationCompletedEvent("ai-simulation:7:completed", 7L, Instant.now())
            : scenario == 1 ? "private-raw-payload".getBytes(java.nio.charset.StandardCharsets.UTF_8) : null;
        var record = new ConsumerRecord<>(kafka.getCompletedTopic(), 3, 42, 7L, value);
        reporter(Runnable::run).report(record, failure, destination("target-notification", "dlt"),
            metadata("target-notification"), null);

        verify(sender).send(anyString(), anyString(),
            org.mockito.ArgumentMatchers.argThat(fields -> (scenario == 0
                ? Long.valueOf(7).equals(fields.get("simulationId"))
                : fields.get("simulationId") == null)
                && !fields.toString().contains("private-raw-payload")));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 32768})
    void 잘못된_시도_횟수는_추정하지_않는다(int attempt) {
        var record = record();
        record.headers().remove(RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS);
        record.headers().add(RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS, ByteBuffer.allocate(4).putInt(attempt).array());
        reporter(Runnable::run).report(record, failure, destination("generation", "dlt"), metadata("generation"), null);

        verify(sender).send(anyString(), anyString(), org.mockito.ArgumentMatchers.argThat(
            fields -> fields.get("attempt") == null));
    }

    @Test
    void 지표_기록_실패도_호출자에게_전파하거나_Discord_통보를_생략하지_않는다() {
        var failing = mock(MeterRegistry.class);
        when(failing.config()).thenReturn(registry.config());
        var reporter = new AiSimulationFailureReporter(properties, kafka, Runnable::run, sender, failing);

        assertThatCode(() -> reporter.report(record(), failure, destination("generation", "dlt"),
            metadata("generation"), null)).doesNotThrowAnyException();
        verify(sender).send(anyString(), anyString(), any());
    }

    private ConsumerRecord<Long, AiSimulationRequestedEvent> record() {
        var record = new ConsumerRecord<>(kafka.getRequestedTopic() + "-generation-retry", 3, 42, 7L,
            new AiSimulationRequestedEvent("ai-simulation:7:requested", 7L, Instant.now()));
        record.headers().add(RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS, ByteBuffer.allocate(4).putInt(4).array());
        return record;
    }

    private TopicPartition destination(String task, String suffix) {
        return new TopicPartition((task.startsWith("requester-") || task.startsWith("target-")
            ? kafka.getCompletedTopic()
            : kafka.getRequestedTopic()) + "-" + task + "-" + suffix, 3);
    }

    private RecordMetadata metadata(String task) {
        return new RecordMetadata(destination(task, "dlt"), 99, 0, 0, 0, 0);
    }
}
