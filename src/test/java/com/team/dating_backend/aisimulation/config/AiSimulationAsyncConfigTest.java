package com.team.dating_backend.aisimulation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class AiSimulationAsyncConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(AiSimulationAsyncConfig.class, TestConfiguration.class)
        .withPropertyValues("app.ai-simulation.async-shutdown-wait-seconds=1");

    @Test
    void 비동기_메서드는_호출자와_다른_전용_스레드에서_실행한다() {
        contextRunner.run(context -> {
            String callerThreadName = Thread.currentThread().getName();
            String workerThreadName = context.getBean(AsyncProbe.class)
                .threadName().get(5, TimeUnit.SECONDS);

            assertThat(workerThreadName).startsWith("ai-simulation-");
            assertThat(workerThreadName).isNotEqualTo(callerThreadName);
        });
    }

    @Test
    void 실행_스레드와_대기_큐가_가득_차면_추가_작업을_거절한다() {
        contextRunner.withPropertyValues(
            "app.ai-simulation.async-pool-size=1",
            "app.ai-simulation.async-queue-capacity=1")
            .run(context -> {
                ThreadPoolTaskExecutor executor = context.getBean(
                    "aiSimulationExecutor", ThreadPoolTaskExecutor.class);
                CountDownLatch started = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                CountDownLatch queuedCompleted = new CountDownLatch(1);
                AtomicBoolean rejectedTaskRan = new AtomicBoolean();

                try {
                    executor.execute(() -> {
                        started.countDown();
                        awaitRelease(release);
                    });
                    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

                    executor.execute(queuedCompleted::countDown);
                    assertThatThrownBy(() -> executor.execute(
                        () -> rejectedTaskRan.set(true)))
                        .isInstanceOf(TaskRejectedException.class);
                    assertThat(rejectedTaskRan).isFalse();

                    release.countDown();
                    assertThat(queuedCompleted.await(5, TimeUnit.SECONDS)).isTrue();
                } finally {
                    release.countDown();
                }
            });
    }

    @Test
    void 종료_대기_시간이_지나도_접수된_작업을_강제_취소하지_않는다() {
        contextRunner.run(context -> {
            ThreadPoolTaskExecutor executor = context.getBean(
                "aiSimulationExecutor", ThreadPoolTaskExecutor.class);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch queuedCompleted = new CountDownLatch(1);

            try {
                executor.execute(() -> {
                    started.countDown();
                    awaitRelease(release);
                });
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                executor.execute(queuedCompleted::countDown);

                executor.shutdown();

                assertThat(queuedCompleted.getCount()).isEqualTo(1);
                assertThatThrownBy(() -> executor.execute(() -> {}))
                    .isInstanceOf(TaskRejectedException.class);

                release.countDown();
                assertThat(queuedCompleted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(executor.getThreadPoolExecutor()
                    .awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
        });
    }

    @Test
    void 유효하지_않은_실행기_설정은_컨텍스트_시작을_실패시킨다() {
        for (String invalidProperty : new String[]{
            "app.ai-simulation.async-pool-size=0",
            "app.ai-simulation.async-queue-capacity=-1",
            "app.ai-simulation.async-shutdown-wait-seconds=-1",
            "app.ai-simulation.max-processing-age-seconds=0",
            "app.ai-simulation.max-processing-age-seconds=600",
            "app.ai-simulation.async-queue-capacity=8"
        }) {
            contextRunner.withPropertyValues(invalidProperty)
                .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void 큐를_늘릴_때_만료_시간도_전체_대기_시간보다_길게_설정한다() {
        contextRunner.withPropertyValues(
            "app.ai-simulation.async-queue-capacity=8",
            "app.ai-simulation.max-processing-age-seconds=1500")
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void 대기열이_스레드_수보다_작아도_실행중_작업의_대기_시간을_포함한다() {
        contextRunner.withPropertyValues(
            "app.ai-simulation.async-pool-size=2",
            "app.ai-simulation.async-queue-capacity=1",
            "app.ai-simulation.max-processing-age-seconds=200")
            .run(context -> assertThat(context).hasFailed());
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Worker release timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiSimulationProperties.class)
    static class TestConfiguration {

        @Bean
        AsyncProbe asyncProbe() {
            return new AsyncProbe();
        }
    }

    public static class AsyncProbe {

        @Async("aiSimulationExecutor")
        public CompletableFuture<String> threadName() {
            return CompletableFuture.completedFuture(Thread.currentThread().getName());
        }
    }
}
