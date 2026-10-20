package com.team.dating_backend.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class NotificationPublishPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void 결과_대기_시간의_기본값은_15초다() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(NotificationPublishProperties.class).getResultTimeoutMs()).isEqualTo(15_000L);
        });
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 25_000L})
    void 양수인_결과_대기_시간을_바인딩한다(long timeoutMs) {
        contextRunner.withPropertyValues("app.notification.publish.result-timeout-ms=" + timeoutMs)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(NotificationPublishProperties.class).getResultTimeoutMs())
                    .isEqualTo(timeoutMs);
            });
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void 결과_대기_시간이_0_이하이면_시작을_거부한다(long timeoutMs) {
        contextRunner.withPropertyValues("app.notification.publish.result-timeout-ms=" + timeoutMs)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationPublishProperties.class)
    static class PropertiesConfiguration {}
}
