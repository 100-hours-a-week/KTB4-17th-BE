package com.team.dating_backend.chat.delegation.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableAsync
@ConditionalOnProperty(
    prefix = "app.chat", name = "runtime-enabled", havingValue = "true"
)
public class ChatDelegationAsyncConfig {

    private static final int POOL_SIZE = 2;

    @Bean("chatDelegationExecutor")
    public ThreadPoolTaskExecutor chatDelegationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(POOL_SIZE);
        executor.setMaxPoolSize(POOL_SIZE);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("chat-delegation-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(100);
        return executor;
    }
}
