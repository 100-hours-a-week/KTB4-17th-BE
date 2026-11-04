package com.team.dating_backend.notification.config;

import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class NotificationAlertConfiguration {

    @Bean("notificationAlertExecutor")
    @DependsOn("notificationDiscordHttpClient")
    public ThreadPoolTaskExecutor notificationAlertExecutor(NotificationAlertProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getPoolSize());
        executor.setMaxPoolSize(properties.getPoolSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("notification-alert-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.getShutdownWaitSeconds());
        return executor;
    }

    @Bean(name = "notificationDiscordHttpClient", destroyMethod = "shutdown")
    public HttpClient notificationDiscordHttpClient(NotificationAlertProperties properties) {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    }

    @Bean("notificationDiscordRestClient")
    public RestClient notificationDiscordRestClient(
        @Qualifier("notificationDiscordHttpClient") HttpClient httpClient,
        NotificationAlertProperties properties,
        JsonMapper jsonMapper) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        return RestClient.builder()
            .requestFactory(requestFactory)
            .configureMessageConverters(converters -> converters
                .withJsonConverter(new JacksonJsonHttpMessageConverter(jsonMapper)))
            .observationRegistry(ObservationRegistry.NOOP)
            .build();
    }
}
