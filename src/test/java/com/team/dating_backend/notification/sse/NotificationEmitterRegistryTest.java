package com.team.dating_backend.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class NotificationEmitterRegistryTest {

    private NotificationEmitterRegistry registry;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        registry = new NotificationEmitterRegistry();
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void 회원별_다중_연결을_보관하고_같은_객체의_반복_등록은_중복을_만들지_않는다() {
        NotificationSseConnection first = connection(1L, "first");
        NotificationSseConnection second = connection(1L, "second");
        NotificationSseConnection otherUser = connection(2L, "first");

        assertThat(registry.register(first)).isTrue();
        assertThat(registry.register(second)).isTrue();
        assertThat(registry.register(otherUser)).isTrue();
        assertThat(registry.register(first)).isTrue();

        assertThat(registry.findByUserId(1L)).containsExactlyInAnyOrder(first, second);
        assertThat(registry.findByUserId(2L)).containsExactly(otherUser);
        assertThat(registry.findByUserId(3L)).isEmpty();
        assertThat(registry.snapshotAll()).containsExactlyInAnyOrder(first, second, otherUser);
    }

    @Test
    void 반복_제거는_다른_연결을_보존하고_마지막_연결_제거_후에도_새_등록이_가능하다() {
        NotificationSseConnection first = connection(1L, "first");
        NotificationSseConnection second = connection(1L, "second");
        registry.register(first);
        registry.register(second);

        registry.remove(first);
        registry.remove(first);

        assertThat(first.isClosed()).isTrue();
        assertThat(second.isClosed()).isFalse();
        assertThat(registry.findByUserId(1L)).containsExactly(second);

        registry.remove(second);

        assertThat(registry.findByUserId(1L)).isEmpty();
        assertThat(registry.snapshotAll()).isEmpty();

        NotificationSseConnection replacement = connection(1L, "replacement");
        assertThat(registry.register(replacement)).isTrue();
        assertThat(registry.findByUserId(1L)).containsExactly(replacement);
    }

    @Test
    void 등록_전에_제거된_연결은_나중에도_등록되지_않는다() {
        NotificationSseConnection connection = connection(1L, "first");

        registry.remove(connection);

        assertThat(connection.isClosed()).isTrue();
        assertThat(registry.register(connection)).isFalse();
        assertThat(registry.findByUserId(1L)).isEmpty();
        assertThat(registry.snapshotAll()).isEmpty();
    }

    @Test
    void 같은_회원과_식별자의_다른_객체는_기존_연결을_덮어쓰거나_제거하지_못한다() {
        NotificationSseConnection original = connection(1L, "same-id");
        NotificationSseConnection conflict = connection(1L, "same-id");
        registry.register(original);

        assertThatThrownBy(() -> registry.register(conflict))
            .isInstanceOfSatisfying(NotificationBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR));

        registry.remove(conflict);

        assertThat(original.isClosed()).isFalse();
        assertThat(registry.findByUserId(1L)).containsExactly(original);
        assertThat(registry.snapshotAll()).containsExactly(original);
    }

    @Test
    void 종료된_연결은_저장소에서_제거되기_전에도_조회에서_제외한다() {
        NotificationSseConnection closed = connection(1L, "closed");
        NotificationSseConnection active = connection(1L, "active");
        registry.register(closed);
        registry.register(active);

        closed.markClosed();

        assertThat(registry.findByUserId(1L)).containsExactly(active);
        assertThat(registry.snapshotAll()).containsExactly(active);
    }

    @Test
    void 마지막_연결_제거와_새_연결_등록이_경합해도_새_연결이_유실되지_않는다() throws Exception {
        for (int round = 0; round < 50; round++) {
            NotificationSseConnection previous = connection(1L, "previous-" + round);
            NotificationSseConnection next = connection(1L, "next-" + round);
            registry.register(previous);

            runConcurrently(() -> registry.remove(previous), () -> registry.register(next));

            assertThat(previous.isClosed()).isTrue();
            assertThat(registry.findByUserId(1L)).containsExactly(next);
            assertThat(registry.snapshotAll()).containsExactly(next);
            registry.remove(next);
        }
    }

    @Test
    void 같은_연결의_등록과_제거가_경합해도_종료된_연결이_다시_등록되지_않는다() throws Exception {
        for (int round = 0; round < 50; round++) {
            NotificationSseConnection connection = connection(1L, "connection-" + round);

            runConcurrently(() -> registry.register(connection), () -> registry.remove(connection));

            assertThat(connection.isClosed()).isTrue();
            assertThat(registry.register(connection)).isFalse();
            assertThat(registry.findByUserId(1L)).isEmpty();
            assertThat(registry.snapshotAll()).isEmpty();
        }
    }

    private void runConcurrently(Runnable first, Runnable second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> firstTask = submit(first, ready, start);
        Future<?> secondTask = submit(second, ready, start);

        try {
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            firstTask.get(5, TimeUnit.SECONDS);
            secondTask.get(5, TimeUnit.SECONDS);
        } finally {
            start.countDown();
        }
    }

    private Future<?> submit(Runnable action, CountDownLatch ready, CountDownLatch start) {
        return executor.submit(() -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError();
            }
            action.run();
            return null;
        });
    }

    private NotificationSseConnection connection(Long userId, String connectionId) {
        return new NotificationSseConnection(
            userId, connectionId, new SseEmitter(), LocalDateTime.of(2026, 10, 15, 12, 0));
    }
}
