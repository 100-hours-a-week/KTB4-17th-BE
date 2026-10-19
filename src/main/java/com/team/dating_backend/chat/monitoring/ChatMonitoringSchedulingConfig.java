package com.team.dating_backend.chat.monitoring;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class ChatMonitoringSchedulingConfig {

    // 지표 집계가 늦어져도 메시지 발행 스케줄을 막지 않도록 분리한다.
    @Bean(name = "chatMonitoringScheduler", defaultCandidate = false)
    public ThreadPoolTaskScheduler chatMonitoringScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("chat-monitoring-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
