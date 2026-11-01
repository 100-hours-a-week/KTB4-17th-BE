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

class NotificationOutboxClaimPropertiesTest {

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
    void 기본_담당_유효기간은_60초다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(NotificationOutboxClaimProperties.class).getDurationMs()).isEqualTo(60_000L);
        });
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 90_000L})
    void 양수인_환경변수로_담당_유효기간을_설정한다(long durationMs) {
        runner.withPropertyValues("APP_NOTIFICATION_OUTBOX_CLAIM_DURATION_MS=" + durationMs).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(NotificationOutboxClaimProperties.class).getDurationMs()).isEqualTo(durationMs);
        });
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void 유효기간이_0_이하이면_에러코드로_시작을_거부한다(long durationMs) {
        runner.withPropertyValues("APP_NOTIFICATION_OUTBOX_CLAIM_DURATION_MS=" + durationMs).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                .isInstanceOfSatisfying(BindValidationException.class,
                    failure -> assertThat(failure.getValidationErrors().getAllErrors())
                        .allSatisfy(error -> assertThat(error.getDefaultMessage()).isEqualTo("INTERNAL_SERVER_ERROR")));
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationOutboxClaimProperties.class)
    static class PropertiesConfiguration {}
}
