package com.team.dating_backend.notification.outbox;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.config.NotificationOutboxRetryProperties;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(NotificationOutboxRetryProperties.class)
public class NotificationOutboxRetryPolicy {

    private final NotificationOutboxRetryProperties properties;

    public boolean isExhausted(int failureCount) {
        requirePositive(failureCount);
        return failureCount >= properties.getMaxFailures();
    }

    public long delayMillis(int failureCount) {
        requirePositive(failureCount);
        long delay = properties.getInitialDelayMs();
        long maximum = properties.getMaxDelayMs();
        for (int attempt = 1; attempt < failureCount && delay < maximum; attempt++) {
            if (delay > maximum / 2) {
                return maximum;
            }
            delay = Math.min(delay * 2, maximum);
        }
        return delay;
    }

    private void requirePositive(int failureCount) {
        if (failureCount <= 0) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
