package com.team.dating_backend.aisimulation.config;

import com.team.dating_backend.common.alert.DiscordWebhookSender;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiSimulationAlertProperties.class)
public class AiSimulationAlertConfiguration {

    @Bean("aiSimulationAlertExecutor")
    @DependsOn("aiSimulationDiscordHttpClient")
    public ThreadPoolTaskExecutor executor(AiSimulationAlertProperties properties) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getPoolSize());
        executor.setMaxPoolSize(properties.getPoolSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("ai-simulation-alert-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.getShutdownWaitSeconds());
        return executor;
    }

    @Bean(name = "aiSimulationDiscordHttpClient", destroyMethod = "shutdown")
    public HttpClient httpClient(AiSimulationAlertProperties properties) {
        return HttpClient.newBuilder().connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
            .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    }

    @Bean("aiSimulationDiscordWebhookSender")
    public DiscordWebhookSender sender(@Qualifier("aiSimulationDiscordHttpClient") HttpClient httpClient,
        AiSimulationAlertProperties properties, JsonMapper jsonMapper) {
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        return new DiscordWebhookSender(RestClient.builder().requestFactory(requestFactory)
            .configureMessageConverters(converters -> converters
                .withJsonConverter(new JacksonJsonHttpMessageConverter(jsonMapper)))
            .observationRegistry(ObservationRegistry.NOOP).build());
    }
}
