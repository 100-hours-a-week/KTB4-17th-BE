package com.team.dating_backend.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.notification.config.NotificationOutboxRetryProperties;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class NotificationOutboxRetryPolicyTest {

    private final NotificationOutboxRetryProperties properties = new NotificationOutboxRetryProperties();
    private final NotificationOutboxRetryPolicy policy = new NotificationOutboxRetryPolicy(properties);

    @ParameterizedTest
    @CsvSource({"1,1000", "2,2000", "3,4000", "4,8000", "6,32000", "7,60000", "2147483647,60000"})
    void 실패_횟수에_따라_대기시간을_늘리고_최대값으로_제한한다(int failures, long delay) {
        assertThat(policy.delayMillis(failures)).isEqualTo(delay);
    }

    @ParameterizedTest
    @CsvSource({"1,false", "4,false", "5,true", "6,true"})
    void 최초_시도를_포함한_실패_한도_소진을_판단한다(int failures, boolean exhausted) {
        assertThat(policy.isExhausted(failures)).isEqualTo(exhausted);
    }

    @Test
    void 큰_대기시간도_곱셈_오버플로_없이_최대값으로_제한한다() {
        properties.setInitialDelayMs(Long.MAX_VALUE / 2 + 1);
        properties.setMaxDelayMs(Long.MAX_VALUE);

        assertThat(policy.delayMillis(2)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void 설정한_한도를_사용하며_한도가_1이면_첫_실패에서_소진된다() {
        properties.setMaxFailures(1);

        assertThat(policy.isExhausted(1)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void 실패_횟수가_양수가_아니면_거부한다(int failures) {
        assertThatThrownBy(() -> policy.delayMillis(failures)).isInstanceOf(NotificationBusinessException.class);
        assertThatThrownBy(() -> policy.isExhausted(failures)).isInstanceOf(NotificationBusinessException.class);
    }
}
