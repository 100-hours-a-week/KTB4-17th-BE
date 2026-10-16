package com.team.dating_backend.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class NotificationSsePropertiesTest {

    @ParameterizedTest
    @ValueSource(strings = {"timeout-ms=0", "heartbeat-delay-ms=-1"})
    void 잘못된_수명_설정은_애플리케이션_시작을_거부한다(String property) {
        new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues("app.notification.sse." + property)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationSseProperties.class)
    static class PropertiesConfiguration {}
}
