package com.team.dating_backend.chat.config;

import com.team.dating_backend.aipractice.service.AiPracticeRedisEventSubscriber;
import com.team.dating_backend.chat.service.ChatMessageRedisSubscriber;
import com.team.dating_backend.chat.service.ChatMessageReadReceiptRedisSubscriber;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "app.chat",
    name = {"runtime-enabled", "realtime-relay.enabled"},
    havingValue = "true"
)
public class ChatRealtimeRedisConfiguration {

    private static final int MESSAGE_POOL_SIZE = 1;

    @Bean("chatRedisMessageExecutor")
    public ThreadPoolTaskExecutor chatRedisMessageExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(MESSAGE_POOL_SIZE);
        executor.setMaxPoolSize(MESSAGE_POOL_SIZE);
        executor.setThreadNamePrefix("chat-redis-message-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    @Bean
    public RedisMessageListenerContainer chatRedisMessageListenerContainer(
        RedisConnectionFactory connectionFactory,
        ChatMessageRedisSubscriber messageSubscriber,
        ChatMessageReadReceiptRedisSubscriber readReceiptSubscriber,
        AiPracticeRedisEventSubscriber aiPracticeSubscriber,
        @Qualifier("chatRedisMessageExecutor") ThreadPoolTaskExecutor messageExecutor,
        @Value("${app.chat.realtime-relay.redis-channel}") String messageChannel,
        @Value("${app.chat.realtime-relay.read-receipt-redis-channel}")
        String readReceiptChannel,
        @Value("${app.chat.realtime-relay.ai-practice-redis-channel}")
        String aiPracticeChannel) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(messageExecutor);
        container.addMessageListener(messageSubscriber, new ChannelTopic(messageChannel));
        container.addMessageListener(readReceiptSubscriber, new ChannelTopic(readReceiptChannel));
        container.addMessageListener(aiPracticeSubscriber, new ChannelTopic(aiPracticeChannel));
        return container;
    }
}
