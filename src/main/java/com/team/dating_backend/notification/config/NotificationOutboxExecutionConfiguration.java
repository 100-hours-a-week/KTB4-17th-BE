package com.team.dating_backend.notification.config;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({NotificationOutboxRelayProperties.class, NotificationOutboxWorkerProperties.class})
public class NotificationOutboxExecutionConfiguration {

    public NotificationOutboxExecutionConfiguration(NotificationOutboxRelayProperties relayProperties,
        NotificationOutboxWorkerProperties workerProperties) {
        if (relayProperties.isEnabled() && workerProperties.isEnabled()) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
