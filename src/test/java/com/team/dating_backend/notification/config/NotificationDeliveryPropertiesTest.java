package com.team.dating_backend.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class NotificationDeliveryPropertiesTest {

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
    void Delivery_소비가_비활성화되어도_발행_결과_대기는_기본_15초다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(NotificationDeliveryProperties.class);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.getResultTimeoutMs()).isEqualTo(15_000L);
        });
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"APP_NOTIFICATION_DELIVERY_RESULT_TIMEOUT_MS",
            "APP_NOTIFICATION_CREATION_DELIVERY_RESULT_TIMEOUT_MS"}
    )
    void 신규와_기존_환경변수는_생성_요청_발행_대기와_독립적으로_적용한다(String variable) {
        runner.withPropertyValues(variable + "=7000", "APP_NOTIFICATION_PUBLISH_RESULT_TIMEOUT_MS=9000")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(NotificationDeliveryProperties.class).getResultTimeoutMs()).isEqualTo(7000L);
                assertThat(context.getBean(NotificationPublishProperties.class).getResultTimeoutMs()).isEqualTo(9000L);
            });
    }

    @ParameterizedTest
    @ValueSource(longs = {7000L, -1L})
    void 신규_환경변수가_있으면_기존_환경변수보다_우선한다(long legacyTimeoutMs) {
        runner.withPropertyValues("APP_NOTIFICATION_DELIVERY_RESULT_TIMEOUT_MS=6000",
            "APP_NOTIFICATION_CREATION_DELIVERY_RESULT_TIMEOUT_MS=" + legacyTimeoutMs)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(NotificationDeliveryProperties.class).getResultTimeoutMs()).isEqualTo(6000L);
            });
    }

    @ParameterizedTest
    @CsvSource(
        {"APP_NOTIFICATION_DELIVERY_RESULT_TIMEOUT_MS,0", "APP_NOTIFICATION_DELIVERY_RESULT_TIMEOUT_MS,-1",
            "APP_NOTIFICATION_CREATION_DELIVERY_RESULT_TIMEOUT_MS,0",
            "APP_NOTIFICATION_CREATION_DELIVERY_RESULT_TIMEOUT_MS,-1"}
    )
    void 선택된_신규와_기존_대기_시간이_0_이하이면_시작을_거부한다(String variable, long timeoutMs) {
        runner.withPropertyValues(variable + "=" + timeoutMs).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
        });
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 25_000L})
    void Delivery_설정_경로에_양수인_대기_시간을_지정한다(long timeoutMs) {
        runner.withPropertyValues("app.notification.delivery.result-timeout-ms=" + timeoutMs).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(NotificationDeliveryProperties.class).getResultTimeoutMs()).isEqualTo(timeoutMs);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({NotificationDeliveryProperties.class, NotificationPublishProperties.class})
    static class PropertiesConfiguration {}
}
