package com.team.dating_backend.notification.config;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class NotificationPublishConfiguration {

    @Bean("notificationPublishExecutor")
    @DependsOn({"kafkaTemplate", "notificationAlertExecutor"})
    public ThreadPoolTaskExecutor notificationPublishExecutor(
        NotificationPublishProperties properties,
        ApplicationContext applicationContext) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor() {
            private final AtomicBoolean shutdownStarted = new AtomicBoolean();

            @Override
            public void onApplicationEvent(ContextClosedEvent event) {
                super.onApplicationEvent(event);
                if (event.getApplicationContext() == applicationContext) {
                    shutdown();
                }
            }

            @Override
            public void shutdown() {
                if (shutdownStarted.compareAndSet(false, true)) {
                    super.shutdown();
                }
            }
        };
        executor.setCorePoolSize(properties.getPoolSize());
        executor.setMaxPoolSize(properties.getPoolSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("notification-publish-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.getShutdownWaitSeconds());
        return executor;
    }
}
