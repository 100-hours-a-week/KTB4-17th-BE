package com.team.dating_backend.aisimulation.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

class AiSimulationKafkaPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class)
        .withInitializer(context -> {
            try {
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
                    .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        });

    @Test
    void 설정_파일에서_기본_실행값과_비활성화된_다섯_그룹을_바인딩한다() {
        contextRunner.withPropertyValues("spring.profiles.active=local").run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(AiSimulationKafkaProperties.class);
            assertThat(properties.getRequestedTopic()).isEqualTo("local-ai-simulation-requested");
            assertThat(properties.getCompletedTopic()).isEqualTo("local-ai-simulation-completed");
            assertThat(properties.getPartitions()).isEqualTo(4);
            assertThat(properties.getConcurrency()).isEqualTo(2);
            assertThat(properties.getPublishResultTimeoutMs()).isEqualTo(15_000L);
            assertThat(Stream.of(properties.getGeneration(), properties.getRequestNotification(),
                properties.getRecommendation(), properties.getRequesterNotification(),
                properties.getTargetNotification()))
                .allSatisfy(group -> {
                    assertThat(group.isEnabled()).isFalse();
                    assertThat(group.getGroupId()).startsWith("local-ai-simulation-");
                });
            assertThat(properties.isConsumerGroupsDistinct()).isTrue();
            assertThat(properties.getGenerationPoll().getMaxPollRecords()).isEqualTo(1);
            assertThat(properties.getGenerationPoll().getMaxPollIntervalMs()).isEqualTo(180_000);
            assertThat(properties.getSideEffectPoll().getMaxPollIntervalMs()).isEqualTo(300_000);
            assertThat(properties.getRetry().getMaxAttempts()).isEqualTo(4);
            assertThat(properties.getRetry().getDelayMs()).isEqualTo(5_000L);
            assertThat(properties.getRetry().getConcurrency()).isEqualTo(1);
        });
    }

    @Test
    void 환경별_이름과_업무별_활성화_및_poll_설정을_독립적으로_바인딩한다() {
        contextRunner.withPropertyValues(
            "spring.profiles.active=dev",
            "AI_SIMULATION_KAFKA_GENERATION_ENABLED=true",
            "AI_SIMULATION_KAFKA_GENERATION_GROUP_ID=custom-generation",
            "AI_SIMULATION_KAFKA_GENERATION_MAX_POLL_INTERVAL_MS=400000",
            "AI_SIMULATION_KAFKA_SIDE_EFFECT_MAX_POLL_RECORDS=10")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var properties = context.getBean(AiSimulationKafkaProperties.class);
                assertThat(properties.getRequestedTopic()).isEqualTo("dev-ai-simulation-requested");
                assertThat(properties.getCompletedTopic()).isEqualTo("dev-ai-simulation-completed");
                assertThat(properties.getGeneration().isEnabled()).isTrue();
                assertThat(properties.getGeneration().getGroupId()).isEqualTo("custom-generation");
                assertThat(properties.getRequestNotification().isEnabled()).isFalse();
                assertThat(properties.getGenerationPoll().getMaxPollIntervalMs()).isEqualTo(400_000);
                assertThat(properties.getSideEffectPoll().getMaxPollIntervalMs()).isEqualTo(300_000);
                assertThat(properties.getGenerationPoll().getMaxPollRecords()).isEqualTo(1);
                assertThat(properties.getSideEffectPoll().getMaxPollRecords()).isEqualTo(10);
            });
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "requested-topic=", "completed-topic=topic/name", "requested-topic=__internal",
            "completed-topic=local-ai-simulation-requested", "partitions=0", "concurrency=0",
            "publish-result-timeout-ms=0", "generation.group-id=",
            "request-notification.group-id=local-ai-simulation-generation",
            "generation-poll.max-poll-records=0", "side-effect-poll.max-poll-interval-ms=0",
            "retry.max-attempts=0", "retry.delay-ms=0", "retry.concurrency=0"}
    )
    void 잘못된_토픽_그룹_실행값은_시작을_거부한다(String property) {
        contextRunner.withPropertyValues("spring.profiles.active=local", "app.ai-simulation.kafka." + property)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiSimulationKafkaProperties.class)
    static class PropertiesConfiguration {}
}
