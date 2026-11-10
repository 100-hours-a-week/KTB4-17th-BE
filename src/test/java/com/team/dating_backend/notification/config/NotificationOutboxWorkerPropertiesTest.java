package com.team.dating_backend.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class NotificationOutboxWorkerPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(NotificationOutboxExecutionConfiguration.class)
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
    void 기본값은_비활성화와_1초_간격과_실행당_100건과_종료_대기_20초다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(NotificationOutboxWorkerProperties.class);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.getFixedDelayMs()).isEqualTo(1000L);
            assertThat(properties.getMaxRequestsPerRun()).isEqualTo(100);
            assertThat(properties.getShutdownWaitSeconds()).isEqualTo(20);
        });
    }

    @Test
    void Worker_환경변수는_Relay_설정과_독립적으로_적용한다() {
        runner.withPropertyValues("APP_NOTIFICATION_OUTBOX_WORKER_ENABLED=true",
            "APP_NOTIFICATION_OUTBOX_WORKER_FIXED_DELAY_MS=25",
            "APP_NOTIFICATION_OUTBOX_WORKER_MAX_REQUESTS_PER_RUN=2",
            "APP_NOTIFICATION_OUTBOX_WORKER_SHUTDOWN_WAIT_SECONDS=0",
            "APP_NOTIFICATION_OUTBOX_RELAY_FIXED_DELAY_MS=3000",
            "APP_NOTIFICATION_OUTBOX_RELAY_BATCH_SIZE=50",
            "APP_NOTIFICATION_OUTBOX_RELAY_SHUTDOWN_WAIT_SECONDS=10")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var worker = context.getBean(NotificationOutboxWorkerProperties.class);
                assertThat(worker.isEnabled()).isTrue();
                assertThat(worker.getFixedDelayMs()).isEqualTo(25L);
                assertThat(worker.getMaxRequestsPerRun()).isEqualTo(2);
                assertThat(worker.getShutdownWaitSeconds()).isZero();
                var relay = context.getBean(NotificationOutboxRelayProperties.class);
                assertThat(relay.isEnabled()).isFalse();
                assertThat(relay.getFixedDelayMs()).isEqualTo(3000L);
                assertThat(relay.getBatchSize()).isEqualTo(50);
                assertThat(relay.getShutdownWaitSeconds()).isEqualTo(10);
            });
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"APP_NOTIFICATION_OUTBOX_WORKER_FIXED_DELAY_MS=0",
            "APP_NOTIFICATION_OUTBOX_WORKER_FIXED_DELAY_MS=-1",
            "APP_NOTIFICATION_OUTBOX_WORKER_MAX_REQUESTS_PER_RUN=0",
            "APP_NOTIFICATION_OUTBOX_WORKER_MAX_REQUESTS_PER_RUN=-1",
            "APP_NOTIFICATION_OUTBOX_WORKER_SHUTDOWN_WAIT_SECONDS=-1"}
    )
    void 잘못된_Worker_실행_설정은_에러코드로_시작을_거부한다(String property) {
        runner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                .isInstanceOfSatisfying(BindValidationException.class,
                    failure -> assertThat(failure.getValidationErrors().getAllErrors())
                        .allSatisfy(error -> assertThat(error.getDefaultMessage()).isEqualTo("INTERNAL_SERVER_ERROR")));
        });
    }

    @Test
    void 환경변수로_Relay와_Worker를_모두_켜면_시작을_거부한다() {
        runner.withPropertyValues("APP_NOTIFICATION_OUTBOX_RELAY_ENABLED=true",
            "APP_NOTIFICATION_OUTBOX_WORKER_ENABLED=true").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOfSatisfying(NotificationBusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR));
            });
    }
}
