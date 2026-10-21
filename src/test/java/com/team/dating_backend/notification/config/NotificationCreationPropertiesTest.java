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

class NotificationCreationPropertiesTest {

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
    void 기본_대기_시간은_15초다() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(NotificationCreationProperties.class).getDeliveryResultTimeoutMs())
                .isEqualTo(15_000L);
        });
    }

    @Test
    void delivery_환경변수는_기존_생성_요청의_대기_설정과_독립적으로_적용한다() {
        contextRunner.withPropertyValues("APP_NOTIFICATION_CREATION_DELIVERY_RESULT_TIMEOUT_MS=7000",
            "APP_NOTIFICATION_PUBLISH_RESULT_TIMEOUT_MS=9000")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(NotificationCreationProperties.class).getDeliveryResultTimeoutMs())
                    .isEqualTo(7000L);
                assertThat(context.getBean(NotificationPublishProperties.class).getResultTimeoutMs()).isEqualTo(9000L);
            });
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void 대기_시간이_0_이하이면_시작을_거부한다(long timeoutMs) {
        contextRunner.withPropertyValues("APP_NOTIFICATION_CREATION_DELIVERY_RESULT_TIMEOUT_MS=" + timeoutMs)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({NotificationCreationProperties.class, NotificationPublishProperties.class})
    static class PropertiesConfiguration {}
}
