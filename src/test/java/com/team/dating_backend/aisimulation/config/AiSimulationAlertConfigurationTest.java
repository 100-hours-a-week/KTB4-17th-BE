package com.team.dating_backend.aisimulation.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.aisimulation.alert.AiSimulationFailureReporter;
import com.team.dating_backend.common.alert.DiscordWebhookSender;
import com.team.dating_backend.notification.config.NotificationAlertConfiguration;
import com.team.dating_backend.notification.config.NotificationAlertProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

class AiSimulationAlertConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(AiSimulationAlertConfiguration.class, AiSimulationFailureReporter.class,
            NotificationAlertConfiguration.class)
        .withBean(NotificationAlertProperties.class, NotificationAlertProperties::new)
        .withBean(AiSimulationKafkaProperties.class, AiSimulationKafkaProperties::new)
        .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
        .withBean(JsonMapper.class, () -> JsonMapper.builder().build());

    @Test
    void 기본_비활성화와_독립_실행기_및_알림용_Sender와의_공존을_확인한다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AiSimulationAlertProperties.class).isEnabled()).isFalse();
            assertThat(context.getBeansOfType(DiscordWebhookSender.class)).containsOnlyKeys(
                "aiSimulationDiscordWebhookSender", "notificationDiscordWebhookSender");
            var executor = context.getBean("aiSimulationAlertExecutor", ThreadPoolTaskExecutor.class);
            assertThat(executor.getQueueCapacity()).isEqualTo(20);
            assertThat(executor.getCorePoolSize()).isEqualTo(executor.getMaxPoolSize()).isEqualTo(1);
            assertThat(context.getBean("aiSimulationDiscordHttpClient", HttpClient.class).connectTimeout())
                .contains(Duration.ofSeconds(2));
        });
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"app.ai-simulation.alert.enabled=true", "app.ai-simulation.alert.pool-size=0",
            "app.ai-simulation.alert.queue-capacity=-1", "app.ai-simulation.alert.max-messages-per-window=0"}
    )
    void 잘못된_통보_설정은_시작할_때_거부한다(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 유효한_웹훅과_외부_설정으로_활성화한다() {
        runner.withPropertyValues("app.ai-simulation.alert.enabled=true",
            "app.ai-simulation.alert.webhook-url=https://discord.com/api/webhooks/123/test-token",
            "app.ai-simulation.alert.pool-size=2", "app.ai-simulation.alert.environment=dev")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var properties = context.getBean(AiSimulationAlertProperties.class);
                assertThat(properties.isEnabled()).isTrue();
                assertThat(properties.getEnvironment()).isEqualTo("dev");
                assertThat(context.getBean("aiSimulationAlertExecutor", ThreadPoolTaskExecutor.class)
                    .getCorePoolSize()).isEqualTo(2);
            });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 환경변수로_활성화하고_기존_웹훅을_공유하거나_전용_웹훅으로_덮어쓴다(boolean dedicated) {
        var configured = runner.withInitializer(context -> {
            try {
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
                    .forEach(context.getEnvironment().getPropertySources()::addLast);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }).withPropertyValues("AI_SIMULATION_DISCORD_ENABLED=true", "AI_SIMULATION_ALERT_ENVIRONMENT=dev",
            "NOTIFICATION_DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/123/shared");
        if (dedicated) {
            configured = configured.withPropertyValues(
                "AI_SIMULATION_DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/456/dedicated");
        }
        configured.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(AiSimulationAlertProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getEnvironment()).isEqualTo("dev");
            assertThat(properties.getWebhookUrl()).endsWith(dedicated ? "/456/dedicated" : "/123/shared");
        });
    }
}
