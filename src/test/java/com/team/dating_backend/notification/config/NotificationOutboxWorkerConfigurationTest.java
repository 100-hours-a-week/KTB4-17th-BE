package com.team.dating_backend.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.team.dating_backend.notification.alert.NotificationFailureReporter;
import com.team.dating_backend.notification.entity.NotificationRequestOutbox;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.outbox.NotificationOutboxWorker;
import com.team.dating_backend.notification.outbox.NotificationOutboxWorkerJob;
import com.team.dating_backend.notification.service.NotificationOutboxClaimService;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class NotificationOutboxWorkerConfigurationTest {

    private final NotificationOutboxClaimService claims = mock(NotificationOutboxClaimService.class);
    private final NotificationOutboxWorker worker = mock(NotificationOutboxWorker.class);
    private final NotificationFailureReporter reporter = mock(NotificationFailureReporter.class);
    private final NotificationRequestOutbox outbox = mock(NotificationRequestOutbox.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
        .withUserConfiguration(NotificationOutboxExecutionConfiguration.class,
            NotificationOutboxWorkerConfiguration.class,
            NotificationOutboxWorkerJob.class, SchedulingConfiguration.class)
        .withBean(NotificationOutboxClaimService.class, () -> claims)
        .withBean(NotificationOutboxWorker.class, () -> worker)
        .withBean(NotificationFailureReporter.class, () -> reporter);

    @BeforeEach
    void setUp() {
        given(claims.tryClaimNext()).willReturn(Optional.empty());
    }

    @Test
    void 설정이_없으면_Worker_작업과_전용_스케줄러를_등록하지_않는다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(NotificationOutboxWorkerJob.class);
            assertThat(context).doesNotHaveBean("notificationOutboxWorkerScheduler");
            verifyNoInteractions(claims, worker, reporter);
        });
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true"})
    void Worker_설정이_켜진_경우에만_작업을_등록한다(boolean relayEnabled, boolean workerEnabled) {
        runner.withPropertyValues("app.notification.outbox.relay.enabled=" + relayEnabled,
            "app.notification.outbox.worker.enabled=" + workerEnabled).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.containsBean("notificationOutboxWorkerJob")).isEqualTo(workerEnabled);
                assertThat(context.containsBean("notificationOutboxWorkerScheduler")).isEqualTo(workerEnabled);
                if (!workerEnabled) {
                    verifyNoInteractions(claims, worker, reporter);
                }
            });
    }

    @Test
    void Relay와_Worker를_동시에_켜면_작업을_시작하지_않는다() {
        runner.withPropertyValues("app.notification.outbox.relay.enabled=true",
            "app.notification.outbox.worker.enabled=true").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause().isInstanceOf(NotificationBusinessException.class);
                verifyNoInteractions(claims, worker, reporter);
            });
    }

    @Test
    void Worker의_응답_대기와_기본_스케줄러의_작업은_독립적으로_실행한다() throws Exception {
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var thread = new AtomicReference<String>();
        given(claims.tryClaimNext()).willReturn(Optional.of(outbox), Optional.empty());
        doAnswer(invocation -> {
            thread.set(Thread.currentThread().getName());
            started.countDown();
            assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(worker).process(outbox);

        enabledRunner().run(context -> {
            try {
                assertThat(context).hasNotFailed();
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                var scheduler = context.getBean("notificationOutboxWorkerScheduler", ThreadPoolTaskScheduler.class);
                assertThat(scheduler.getPoolSize()).isEqualTo(1);
                assertThat(thread.get()).startsWith("notification-outbox-worker-");
                assertThat(context.getBean("taskScheduler")).isNotSameAs(scheduler);
                assertThat(context.getBean(Heartbeat.class).calls.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                finish.countDown();
            }
        });
    }

    @Test
    void 종료_시_의존_자원이_열린_동안_대기를_중단하고_추가_점유를_막는다() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var dependenciesOpen = new AtomicBoolean();
        var kafka = new AtomicReference<CloseProbe>();
        var alert = new AtomicReference<CloseProbe>();
        given(claims.tryClaimNext()).willReturn(Optional.of(outbox));
        doAnswer(invocation -> {
            started.countDown();
            try {
                new CountDownLatch(1).await(10, TimeUnit.SECONDS);
                return null;
            } catch (InterruptedException failure) {
                dependenciesOpen.set(!kafka.get().closed.get() && !alert.get().closed.get());
                interrupted.countDown();
                throw failure;
            }
        }).when(worker).process(outbox);

        enabledRunner().run(context -> {
            kafka.set(context.getBean("kafkaTemplate", CloseProbe.class));
            alert.set(context.getBean("notificationAlertExecutor", CloseProbe.class));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            context.close();

            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(dependenciesOpen.get()).isTrue();
            assertThat(kafka.get().closed.get()).isTrue();
            assertThat(alert.get().closed.get()).isTrue();
            verify(claims).tryClaimNext();
        });
    }

    private ApplicationContextRunner enabledRunner() {
        return runner.withPropertyValues("app.notification.outbox.worker.enabled=true",
            "app.notification.outbox.worker.fixed-delay-ms=25",
            "app.notification.outbox.worker.shutdown-wait-seconds=2");
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
