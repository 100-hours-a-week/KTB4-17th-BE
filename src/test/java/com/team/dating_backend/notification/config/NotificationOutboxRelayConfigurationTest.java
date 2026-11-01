package com.team.dating_backend.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.notification.alert.NotificationFailureReporter;
import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.enums.NotificationType;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.outbox.NotificationOutboxPublishJob;
import com.team.dating_backend.notification.outbox.NotificationOutboxRelay;
import com.team.dating_backend.notification.service.NotificationRequestOutboxService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

class NotificationOutboxRelayConfigurationTest {

    private final NotificationRequestOutboxService outboxService = mock(NotificationRequestOutboxService.class);
    private final NotificationOutboxRelay relay = mock(NotificationOutboxRelay.class);
    private final NotificationFailureReporter reporter = mock(NotificationFailureReporter.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
        .withUserConfiguration(NotificationOutboxRelayConfiguration.class, NotificationOutboxPublishJob.class,
            SchedulingConfiguration.class)
        .withBean(NotificationRequestOutboxService.class, () -> outboxService)
        .withBean(NotificationOutboxRelay.class, () -> relay)
        .withBean(NotificationFailureReporter.class, () -> reporter);

    @Test
    void 비활성화하면_발행_작업과_전용_스케줄러를_등록하지_않는다() {
        runner.withPropertyValues("app.notification.outbox.relay.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(NotificationOutboxPublishJob.class);
            assertThat(context).doesNotHaveBean("notificationOutboxScheduler");
            verifyNoInteractions(outboxService, relay, reporter);
        });
    }

    @Test
    void 설정이_없어도_발행_작업은_비활성화된다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(NotificationOutboxPublishJob.class);
            assertThat(context).doesNotHaveBean("notificationOutboxScheduler");
        });
    }

    @Test
    void Kafka_응답을_기다리는_동안에도_기본_스케줄러의_다른_작업은_실행된다() {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var thread = new AtomicReference<String>();
        given(outboxService.findAvailable(2)).willReturn(List.of(outbox(1L)));
        doAnswer(invocation -> {
            thread.set(Thread.currentThread().getName());
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return true;
        }).when(relay).publish(1L);

        enabledRunner().run(context -> {
            try {
                assertThat(context).hasNotFailed();
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                var scheduler = context.getBean("notificationOutboxScheduler", ThreadPoolTaskScheduler.class);
                assertThat(scheduler.getPoolSize()).isEqualTo(1);
                assertThat(thread.get()).startsWith("notification-outbox-");
                assertThat(context.getBean("taskScheduler")).isNotSameAs(scheduler);
                assertThat(context.getBean(Heartbeat.class).calls.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
        });
    }

    @Test
    void 종료_이벤트는_현재_대기를_중단하고_다음_후보와_다음_실행을_막는다() {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var dependenciesOpen = new AtomicBoolean();
        var kafka = new AtomicReference<CloseProbe>();
        var alert = new AtomicReference<CloseProbe>();
        given(outboxService.findAvailable(2)).willReturn(List.of(outbox(1L), outbox(2L)));
        doAnswer(invocation -> {
            started.countDown();
            try {
                new CountDownLatch(1).await(10, TimeUnit.SECONDS);
                return true;
            } catch (InterruptedException failure) {
                dependenciesOpen.set(!kafka.get().closed.get() && !alert.get().closed.get());
                interrupted.countDown();
                throw new NotificationBusinessException(NotificationErrorCode.OUTBOX_PUBLISH_INTERRUPTED,
                    failure);
            }
        }).when(relay).publish(1L);

        enabledRunner().run(context -> {
            kafka.set(context.getBean("kafkaTemplate", CloseProbe.class));
            alert.set(context.getBean("notificationAlertExecutor", CloseProbe.class));

            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            context.close();

            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(dependenciesOpen.get()).isTrue();
            assertThat(kafka.get().closed.get()).isTrue();
            assertThat(alert.get().closed.get()).isTrue();
            assertThat(context.getSourceApplicationContext().isActive()).isFalse();
            verify(relay, never()).publish(2L);
        });
    }

    private ApplicationContextRunner enabledRunner() {
        return runner.withPropertyValues("app.notification.outbox.relay.enabled=true",
            "app.notification.outbox.relay.fixed-delay-ms=50", "app.notification.outbox.relay.batch-size=2",
            "app.notification.outbox.relay.shutdown-wait-seconds=2");
    }

    private NotificationRequestOutbox outbox(Long id) {
        var outbox = new NotificationRequestOutbox("source-" + id, 7L, NotificationType.LIKE, "{}",
            LocalDateTime.now());
        ReflectionTestUtils.setField(outbox, "id", id);
        return outbox;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingConfiguration {

        @Bean("kafkaTemplate")
        CloseProbe kafka() {
            return new CloseProbe();
        }

        @Bean("notificationAlertExecutor")
        CloseProbe alert() {
            return new CloseProbe();
        }

        @Bean
        Heartbeat heartbeat() {
            return new Heartbeat();
        }
    }

    static class CloseProbe implements AutoCloseable {

        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void close() {
            closed.set(true);
        }
    }

    static class Heartbeat {

        private final CountDownLatch calls = new CountDownLatch(3);

        @Scheduled(fixedDelay = 20)
        public void tick() {
            calls.countDown();
        }
    }
}
