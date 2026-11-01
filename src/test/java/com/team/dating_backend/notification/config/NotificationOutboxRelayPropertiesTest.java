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

class NotificationOutboxRelayPropertiesTest {

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
    void 기본값은_비활성화와_1초_간격과_배치_100개와_종료_대기_20초다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(NotificationOutboxRelayProperties.class);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.getFixedDelayMs()).isEqualTo(1000L);
            assertThat(properties.getBatchSize()).isEqualTo(100);
            assertThat(properties.getShutdownWaitSeconds()).isEqualTo(20);
        });
    }

    @Test
    void 환경변수로_실행_여부와_처리량을_설정한다() {
        runner.withPropertyValues("APP_NOTIFICATION_OUTBOX_RELAY_ENABLED=true",
            "APP_NOTIFICATION_OUTBOX_RELAY_FIXED_DELAY_MS=25", "APP_NOTIFICATION_OUTBOX_RELAY_BATCH_SIZE=2",
            "APP_NOTIFICATION_OUTBOX_RELAY_SHUTDOWN_WAIT_SECONDS=0")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var properties = context.getBean(NotificationOutboxRelayProperties.class);
                assertThat(properties.isEnabled()).isTrue();
                assertThat(properties.getFixedDelayMs()).isEqualTo(25);
                assertThat(properties.getBatchSize()).isEqualTo(2);
                assertThat(properties.getShutdownWaitSeconds()).isZero();
            });
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"APP_NOTIFICATION_OUTBOX_RELAY_FIXED_DELAY_MS=0",
            "APP_NOTIFICATION_OUTBOX_RELAY_FIXED_DELAY_MS=-1", "APP_NOTIFICATION_OUTBOX_RELAY_BATCH_SIZE=0",
            "APP_NOTIFICATION_OUTBOX_RELAY_BATCH_SIZE=-1", "APP_NOTIFICATION_OUTBOX_RELAY_SHUTDOWN_WAIT_SECONDS=-1"}
    )
    void 잘못된_실행_설정은_에러코드로_시작을_거부한다(String property) {
        runner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                .isInstanceOfSatisfying(BindValidationException.class,
                    failure -> assertThat(failure.getValidationErrors().getAllErrors())
                        .allSatisfy(error -> assertThat(error.getDefaultMessage()).isEqualTo("INTERNAL_SERVER_ERROR")));
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationOutboxRelayProperties.class)
    static class PropertiesConfiguration {}
}
