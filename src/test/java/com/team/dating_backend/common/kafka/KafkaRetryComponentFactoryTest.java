package com.team.dating_backend.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.team.dating_backend.common.enums.CommonErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.retrytopic.DeadLetterPublishingRecovererFactory;
import org.springframework.kafka.retrytopic.DestinationTopicResolver;

class KafkaRetryComponentFactoryTest {

    @Test
    void 정책은_복구_객체_생성_시_조회하고_도메인_리포터에도_공통_안전_설정을_적용한다() {
        var policy = mock(KafkaRetryPolicy.class);
        when(policy.listenerIds()).thenReturn(Set.of("test-listener"));
        when(policy.publishResultTimeoutMs()).thenReturn(12345L);
        var recoverer = mock(DeadLetterPublishingRecoverer.class);
        doAnswer(invocation -> {
            var factory = invocation.getArgument(0, DeadLetterPublishingRecovererFactory.class);
            factory.setDeadLetterPublisherCreator((template, destination) -> recoverer);
            return null;
        }).when(policy).customizeRecovererFactory(any());
        var lookups = new AtomicInteger();
        var components = new KafkaRetryComponentFactory(() -> {
            lookups.incrementAndGet();
            return List.of(policy);
        });
        var factory = components.deadLetterPublishingRecovererFactory(mock(DestinationTopicResolver.class));

        assertThat(lookups.get()).isZero();
        assertThat(factory.create("test-listener")).isSameAs(recoverer);
        assertThat(lookups.get()).isEqualTo(1);
        verify(recoverer, atLeastOnce()).setFailIfSendResultIsError(true);
        verify(recoverer).setWaitForSendResultTimeout(Duration.ofMillis(12345L));
        verify(recoverer).setThrowIfNoDestinationReturned(true);
        verify(recoverer, atLeastOnce()).setLogRecoveryRecord(false);
    }

    @Test
    void 하나의_Listener에_여러_도메인_정책이_겹치면_임의로_선택하지_않는다() {
        var first = mock(KafkaRetryPolicy.class);
        var second = mock(KafkaRetryPolicy.class);
        when(first.listenerIds()).thenReturn(Set.of("duplicate"));
        when(second.listenerIds()).thenReturn(Set.of("duplicate"));
        var factory = new KafkaRetryComponentFactory(() -> List.of(first, second))
            .deadLetterPublishingRecovererFactory(mock(DestinationTopicResolver.class));

        assertThatThrownBy(() -> factory.create("duplicate"))
            .isInstanceOf(BeanInitializationException.class)
            .hasMessage(CommonErrorCode.INTERNAL_SERVER_ERROR.name());
    }
}
