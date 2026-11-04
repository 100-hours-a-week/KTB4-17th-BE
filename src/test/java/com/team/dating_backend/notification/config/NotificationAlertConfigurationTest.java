package com.team.dating_backend.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.notification.alert.DiscordWebhookClient;
import com.team.dating_backend.notification.alert.NotificationAlertDispatcher;
import com.team.dating_backend.notification.alert.NotificationFailureReporter;
import com.team.dating_backend.notification.enums.NotificationFailureCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

class NotificationAlertConfigurationTest {

    private static final String WEBHOOK = "https://discord.com/api/webhooks/123/test-token";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(NotificationAlertConfiguration.class, PropertiesConfiguration.class,
            DiscordWebhookClient.class, NotificationAlertDispatcher.class, NotificationFailureReporter.class)
        .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
        .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
        .withInitializer(context -> {
            var sources = context.getEnvironment().getPropertySources();
            sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            try {
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
                    .forEach(sources::addLast);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        });

    @Test
    void 기본_설정은_통보를_비활성화하고_실패는_로컬에_기록한다() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(NotificationAlertProperties.class);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.getWebhookUrl()).isEmpty();
            var executor = context.getBean("notificationAlertExecutor", ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(executor.getMaxPoolSize());
            assertThat(executor.getQueueCapacity()).isEqualTo(20);
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
            var httpClient = context.getBean("notificationDiscordHttpClient", HttpClient.class);
            assertThat(httpClient.connectTimeout()).contains(Duration.ofSeconds(2));
            assertThat(httpClient.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);

            context.getBean(NotificationFailureReporter.class)
                .report(NotificationFailureCode.PUBLISH_CALL_FAILED, null, null);
            assertThat(context.getBean(SimpleMeterRegistry.class).get("notification.failures").counter().count())
                .isEqualTo(1);
            assertThat(executor.getThreadPoolExecutor().getTaskCount()).isZero();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void 개발과_운영은_같은_webhook과_각각의_환경_이름을_사용한다(String profile) {
        contextRunner.withPropertyValues("SPRING_PROFILES_ACTIVE=" + profile,
            "NOTIFICATION_DISCORD_ENABLED=true", "NOTIFICATION_DISCORD_WEBHOOK_URL=" + WEBHOOK)
            .run(context -> {
                assertThat(context).hasNotFailed();
                var properties = context.getBean(NotificationAlertProperties.class);
                assertThat(properties.isEnabled()).isTrue();
                assertThat(properties.getEnvironment()).isEqualTo(profile);
                assertThat(properties.getWebhookUrl()).isEqualTo(WEBHOOK);
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "https://example.invalid/private-token"})
    void 활성화된_webhook이_잘못되면_URL을_노출하지_않고_시작을_거부한다(String webhook) {
        contextRunner.withPropertyValues("NOTIFICATION_DISCORD_ENABLED=true",
            "NOTIFICATION_DISCORD_WEBHOOK_URL=" + webhook)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
                var stackTrace = new StringWriter();
                context.getStartupFailure().printStackTrace(new PrintWriter(stackTrace));
                assertThat(stackTrace.toString()).doesNotContain("private-token");
            });
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"NOTIFICATION_ALERT_POOL_SIZE=0", "NOTIFICATION_ALERT_QUEUE_CAPACITY=-1",
            "NOTIFICATION_ALERT_MAX_MESSAGES_PER_WINDOW=0", "NOTIFICATION_ALERT_RATE_LIMIT_WINDOW_MS=0"}
    )
    void 실행_자원과_빈도_제한의_잘못된_설정을_거부한다(String property) {
        contextRunner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationAlertProperties.class)
    static class PropertiesConfiguration {}
}
