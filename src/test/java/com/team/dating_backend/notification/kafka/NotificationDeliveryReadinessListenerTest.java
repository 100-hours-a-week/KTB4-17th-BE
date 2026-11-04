package com.team.dating_backend.notification.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.team.dating_backend.notification.config.NotificationDeliveryProperties;
import com.team.dating_backend.notification.config.NotificationKafkaProperties;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import com.team.dating_backend.notification.sse.NotificationDeliveryReadinessTracker;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.kafka.event.ConsumerStoppingEvent;
import org.springframework.kafka.listener.MessageListenerContainer;

class NotificationDeliveryReadinessListenerTest {

    private final NotificationKafkaProperties kafka = properties();
    private final NotificationDeliveryReadinessTracker tracker = new NotificationDeliveryReadinessTracker(kafka);
    private final NotificationDeliveryReadinessListener listener = new NotificationDeliveryReadinessListener(
        tracker, kafka, new NotificationDeliveryProperties());
    private final TopicPartition first = new TopicPartition("delivery", 0);
    private final TopicPartition second = new TopicPartition("delivery", 1);

    @Test
    void 모든_Consumer의_전체_Partition_위치를_확인한_뒤에만_준비된다() {
        var a = consumer(Set.of(first), List.of(info(0), info(1)));
        var b = consumer(Set.of(second), List.of(info(0), info(1)));
        assertThat(tracker.isReady()).isFalse();
        listener.onPartitionsAssigned(a, Set.of(first));
        assertThat(tracker.isReady()).isFalse();
        doAnswer(invocation -> {
            assertThat(tracker.isReady()).isFalse();
            return 17L;
        }).when(b).position(eq(second), any(Duration.class));
        listener.onPartitionsAssigned(b, Set.of(second));
        assertThat(tracker.isReady()).isTrue();
    }

    @Test
    void 추가_할당_콜백도_Consumer가_현재_맡은_전체_목록을_다시_확인한다() {
        var consumer = consumer(Set.of(first), List.of(info(0)));
        listener.onPartitionsAssigned(consumer, Set.of(first));
        assertThat(tracker.isReady()).isTrue();
        when(consumer.partitionsFor(eq("delivery"), any(Duration.class))).thenReturn(List.of(info(0), info(1)));
        when(consumer.assignment()).thenReturn(Set.of(first, second));
        doAnswer(invocation -> {
            assertThat(tracker.isReady()).isFalse();
            return 23L;
        }).when(consumer).position(eq(first), any(Duration.class));
        listener.onPartitionsAssigned(consumer, Set.of(second));
        assertThat(tracker.isReady()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(Invalidation.class)
    void 회수_상실_종료는_준비를_무효화하고_새_할당은_다시_확인한다(Invalidation invalidation) {
        var consumer = consumer(Set.of(first), List.of(info(0)));
        listener.onPartitionsAssigned(consumer, Set.of(first));
        var container = mock(MessageListenerContainer.class);
        when(container.getListenerId()).thenReturn("notificationCreationConsumer");
        listener.onConsumerStopping(new ConsumerStoppingEvent(container, container, consumer, Set.of(first)));
        assertThat(tracker.isReady()).isTrue();
        switch (invalidation) {
            case REVOKED -> listener.onPartitionsRevokedBeforeCommit(consumer, Set.of(first));
            case LOST -> listener.onPartitionsLost(consumer, Set.of(first));
            case STOPPING -> {
                when(container.getListenerId()).thenReturn("notificationDeliveryConsumer");
                listener.onConsumerStopping(new ConsumerStoppingEvent(container, container, consumer, Set.of(first)));
            }
        }
        assertThat(tracker.isReady()).isFalse();
        listener.onPartitionsAssigned(consumer, Set.of(first));
        assertThat(tracker.isReady()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("lateResults")
    void metadata나_위치_조회_도중_무효화된_이전_결과와_실패는_새_할당을_덮지_않는다(Stage stage, boolean failure) throws Exception {
        var consumer = consumer(Set.of(first), List.of(info(0)));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var answer = (org.mockito.stubbing.Answer<Object>) invocation -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                if (failure) {
                    throw new IllegalStateException();
                }
            }
            return stage == Stage.METADATA ? List.of(info(0)) : 17L;
        };
        if (stage == Stage.METADATA) {
            doAnswer(answer).when(consumer).partitionsFor(eq("delivery"), any(Duration.class));
        } else {
            doAnswer(answer).when(consumer).position(eq(first), any(Duration.class));
        }
        try (var executor = Executors.newSingleThreadExecutor()) {
            var previous = executor.submit(() -> listener.onPartitionsAssigned(consumer, Set.of(first)));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                tracker.invalidateAll();
                assertThat(tracker.isReady()).isFalse();
                listener.onPartitionsAssigned(consumer, Set.of(first));
                assertThat(tracker.isReady()).isTrue();
            } finally {
                release.countDown();
            }
            if (failure) {
                assertThatThrownBy(() -> previous.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(NotificationBusinessException.class);
            } else {
                previous.get(5, TimeUnit.SECONDS);
            }
            assertThat(tracker.isReady()).isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(PreparationFailure.class)
    void 위치_확인_실패는_준비되지_않은_상태와_원인을_유지한다(PreparationFailure failure) {
        var consumer = consumer(Set.of(first), List.of(info(0)));
        var expected = new AtomicReference<Throwable>();
        doAnswer(invocation -> {
            Throwable cause = switch (failure) {
                case TIMEOUT -> new org.apache.kafka.common.errors.TimeoutException();
                case INTERRUPT -> new InterruptException(new InterruptedException());
                case ERROR -> new AssertionError();
            };
            expected.set(cause);
            throw cause;
        }).when(consumer).position(eq(first), any(Duration.class));
        try {
            Throwable thrown = catchThrowable(() -> listener.onPartitionsAssigned(consumer, Set.of(first)));
            assertThat(expected.get()).isNotNull();
            if (failure == PreparationFailure.ERROR) {
                assertThat(thrown).isSameAs(expected.get());
            } else {
                assertThat(thrown).isInstanceOf(NotificationBusinessException.class);
                assertThat(thrown.getCause()).isSameAs(expected.get());
            }
            assertThat(tracker.isReady()).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isEqualTo(failure == PreparationFailure.INTERRUPT);
        } finally {
            Thread.interrupted();
        }
    }

    @SuppressWarnings("unchecked")
    private Consumer<String, Object> consumer(Set<TopicPartition> assigned, List<PartitionInfo> metadata) {
        Consumer<String, Object> consumer = mock(Consumer.class);
        when(consumer.partitionsFor(eq("delivery"), any(Duration.class))).thenReturn(metadata);
        when(consumer.assignment()).thenReturn(assigned);
        when(consumer.position(any(TopicPartition.class), any(Duration.class))).thenReturn(17L);
        return consumer;
    }

    private PartitionInfo info(int partition) {
        return new PartitionInfo("delivery", partition, null, new Node[0], new Node[0]);
    }

    private static NotificationKafkaProperties properties() {
        var properties = new NotificationKafkaProperties();
        properties.setDeliveryTopic("delivery");
        return properties;
    }

    private static Stream<Arguments> lateResults() {
        return Stream.of(Arguments.of(Stage.METADATA, false), Arguments.of(Stage.METADATA, true),
            Arguments.of(Stage.POSITION, false), Arguments.of(Stage.POSITION, true));
    }

    private enum PreparationFailure {
        TIMEOUT, INTERRUPT, ERROR
    }

    private enum Stage {
        METADATA, POSITION
    }
    private enum Invalidation {
        REVOKED, LOST, STOPPING
    }
}
