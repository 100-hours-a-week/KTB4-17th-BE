package com.team.dating_backend.notification.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.notification.outbox.worker", name = "enabled", havingValue = "true")
@Import(NotificationOutboxExecutionConfiguration.class)
public class NotificationOutboxWorkerConfiguration {

    @Bean(name = "notificationOutboxWorkerScheduler", defaultCandidate = false)
    @DependsOn({"kafkaTemplate", "notificationAlertExecutor"})
    public ThreadPoolTaskScheduler notificationOutboxWorkerScheduler(NotificationOutboxWorkerProperties properties) {
        var scheduler = new ThreadPoolTaskScheduler() {
            @Override
            protected void initiateEarlyShutdown() {
                shutdown();
            }
        };
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("notification-outbox-worker-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setAwaitTerminationSeconds(properties.getShutdownWaitSeconds());
        return scheduler;
    }
}
