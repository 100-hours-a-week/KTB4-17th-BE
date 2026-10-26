package com.team.dating_backend.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class NotificationSsePropertiesTest {

    @ParameterizedTest
    @ValueSource(
        strings = {"timeout-ms=0", "heartbeat-delay-ms=-1", "drain-timeout-ms=-1",
            "close-timeout-ms=0", "consumer-stop-timeout-ms=0"}
    )
    void 잘못된_수명_설정은_애플리케이션_시작을_거부한다(String property) {
        new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues("app.notification.sse." + property)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
            });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 실제_YAML의_기본값과_환경변수_재정의_및_드레인_생략을_바인딩한다(boolean override) {
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class)
            .withInitializer(context -> {
                var sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                if (override) {
                    sources.addFirst(new SystemEnvironmentPropertySource("test-env", Map.of(
                        "APP_NOTIFICATION_SSE_DRAIN_TIMEOUT_MS", "0",
                        "APP_NOTIFICATION_SSE_CLOSE_TIMEOUT_MS", "37",
                        "APP_NOTIFICATION_SSE_CONSUMER_STOP_TIMEOUT_MS", "83")));
                }
                try {
                    new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
                        .forEach(sources::addLast);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }).run(context -> {
                assertThat(context).hasNotFailed();
                var properties = context.getBean(NotificationSseProperties.class);
                assertThat(properties.getDrainTimeoutMs()).isEqualTo(override ? 0 : 10_000);
                assertThat(properties.getCloseTimeoutMs()).isEqualTo(override ? 37 : 5_000);
                assertThat(properties.getConsumerStopTimeoutMs()).isEqualTo(override ? 83 : 10_000);
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationSseProperties.class)
    static class PropertiesConfiguration {}
}
