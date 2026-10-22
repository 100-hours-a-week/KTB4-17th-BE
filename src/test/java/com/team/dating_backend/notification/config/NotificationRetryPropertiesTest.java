package com.team.dating_backend.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
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

class NotificationRetryPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class)
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
    void 기본_환경변수를_총_시도_횟수와_지수_backoff_설정에_바인딩한다() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(NotificationRetryProperties.class);
            assertThat(properties.getMaxAttempts()).isEqualTo(7);
            assertThat(properties.getInitialDelayMs()).isEqualTo(1_000L);
            assertThat(properties.getMultiplier()).isEqualTo(2.0);
            assertThat(properties.getMaxDelayMs()).isEqualTo(30_000L);
            assertThat(properties.getConcurrency()).isEqualTo(1);
            assertThat(properties.getPublishResultTimeoutMs()).isEqualTo(15_000L);
        });
    }

    @Test
    void 모든_Retry_환경변수로_초기값을_재정의한다() {
        contextRunner.withPropertyValues(
            "APP_NOTIFICATION_RETRY_MAX_ATTEMPTS=4",
            "APP_NOTIFICATION_RETRY_INITIAL_DELAY_MS=250",
            "APP_NOTIFICATION_RETRY_MULTIPLIER=1.5",
            "APP_NOTIFICATION_RETRY_MAX_DELAY_MS=2000",
            "APP_NOTIFICATION_RETRY_CONCURRENCY=3",
            "APP_NOTIFICATION_RETRY_PUBLISH_RESULT_TIMEOUT_MS=5000")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var properties = context.getBean(NotificationRetryProperties.class);
                assertThat(properties.getMaxAttempts()).isEqualTo(4);
                assertThat(properties.getInitialDelayMs()).isEqualTo(250L);
                assertThat(properties.getMultiplier()).isEqualTo(1.5);
                assertThat(properties.getMaxDelayMs()).isEqualTo(2_000L);
                assertThat(properties.getConcurrency()).isEqualTo(3);
                assertThat(properties.getPublishResultTimeoutMs()).isEqualTo(5_000L);
            });
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "APP_NOTIFICATION_RETRY_MAX_ATTEMPTS=0",
            "APP_NOTIFICATION_RETRY_INITIAL_DELAY_MS=0",
            "APP_NOTIFICATION_RETRY_MAX_DELAY_MS=-1",
            "APP_NOTIFICATION_RETRY_CONCURRENCY=0",
            "APP_NOTIFICATION_RETRY_PUBLISH_RESULT_TIMEOUT_MS=0",
            "APP_NOTIFICATION_RETRY_MULTIPLIER=1",
            "APP_NOTIFICATION_RETRY_MULTIPLIER=NaN",
            "APP_NOTIFICATION_RETRY_MULTIPLIER=Infinity",
            "APP_NOTIFICATION_RETRY_MAX_DELAY_MS=1000"
        }
    )
    void 유효하지_않은_재시도_설정은_시작을_거부한다(String property) {
        contextRunner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationRetryProperties.class)
    static class PropertiesConfiguration {}
}
