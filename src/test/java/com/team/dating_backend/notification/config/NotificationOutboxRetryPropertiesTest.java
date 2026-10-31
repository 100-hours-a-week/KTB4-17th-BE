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

class NotificationOutboxRetryPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class)
        .withInitializer(context -> {
            var sources = context.getEnvironment().getPropertySources();
            sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            try {
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
                    .forEach(sources::addLast);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });

    @Test
    void 기본_설정은_실패_5회와_1초부터_최대_60초_대기다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(NotificationOutboxRetryProperties.class);
            assertThat(properties.getMaxFailures()).isEqualTo(5);
            assertThat(properties.getInitialDelayMs()).isEqualTo(1000);
            assertThat(properties.getMaxDelayMs()).isEqualTo(60_000);
        });
    }

    @Test
    void Consumer_Retry_Topic과_별개의_환경변수로_설정한다() {
        runner.withPropertyValues("APP_NOTIFICATION_OUTBOX_RETRY_MAX_FAILURES=3",
            "APP_NOTIFICATION_OUTBOX_RETRY_INITIAL_DELAY_MS=25", "APP_NOTIFICATION_OUTBOX_RETRY_MAX_DELAY_MS=100")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var properties = context.getBean(NotificationOutboxRetryProperties.class);
                assertThat(properties.getMaxFailures()).isEqualTo(3);
                assertThat(properties.getInitialDelayMs()).isEqualTo(25);
                assertThat(properties.getMaxDelayMs()).isEqualTo(100);
            });
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"APP_NOTIFICATION_OUTBOX_RETRY_MAX_FAILURES=0", "APP_NOTIFICATION_OUTBOX_RETRY_MAX_FAILURES=-1",
            "APP_NOTIFICATION_OUTBOX_RETRY_INITIAL_DELAY_MS=0", "APP_NOTIFICATION_OUTBOX_RETRY_MAX_DELAY_MS=0",
            "APP_NOTIFICATION_OUTBOX_RETRY_MAX_DELAY_MS=999"}
    )
    void 잘못된_한도와_대기시간_범위는_시작을_거부한다(String property) {
        runner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                .isInstanceOfSatisfying(BindValidationException.class,
                    failure -> assertThat(failure.getValidationErrors().getAllErrors())
                        .allSatisfy(error -> assertThat(error.getDefaultMessage()).isEqualTo("INTERNAL_SERVER_ERROR")));
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationOutboxRetryProperties.class)
    static class PropertiesConfiguration {}
}
