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

class NotificationPublishPropertiesTest {

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
    void 기본_설정은_15초_대기와_고정_pool_2개_대기열_100개_종료_대기_20초다() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(NotificationPublishProperties.class);
            assertThat(properties.getResultTimeoutMs()).isEqualTo(15_000L);
            assertThat(properties.getPoolSize()).isEqualTo(2);
            assertThat(properties.getQueueCapacity()).isEqualTo(100);
            assertThat(properties.getShutdownWaitSeconds()).isEqualTo(20);
        });
    }

    @ParameterizedTest
    @CsvSource({"1, 0, 0", "3, 25, 30"})
    void 환경변수로_실행_자원을_설정하며_대기열과_종료_대기는_0을_허용한다(int pool, int queue, int shutdown) {
        contextRunner.withPropertyValues("APP_NOTIFICATION_PUBLISH_POOL_SIZE=" + pool,
            "APP_NOTIFICATION_PUBLISH_QUEUE_CAPACITY=" + queue,
            "APP_NOTIFICATION_PUBLISH_SHUTDOWN_WAIT_SECONDS=" + shutdown)
            .run(context -> {
                assertThat(context).hasNotFailed();
                var properties = context.getBean(NotificationPublishProperties.class);
                assertThat(properties.getPoolSize()).isEqualTo(pool);
                assertThat(properties.getQueueCapacity()).isEqualTo(queue);
                assertThat(properties.getShutdownWaitSeconds()).isEqualTo(shutdown);
            });
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"APP_NOTIFICATION_PUBLISH_POOL_SIZE=0", "APP_NOTIFICATION_PUBLISH_POOL_SIZE=-1",
            "APP_NOTIFICATION_PUBLISH_QUEUE_CAPACITY=-1", "APP_NOTIFICATION_PUBLISH_SHUTDOWN_WAIT_SECONDS=-1"}
    )
    void 잘못된_실행_자원_설정은_시작을_거부한다(String property) {
        contextRunner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
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
