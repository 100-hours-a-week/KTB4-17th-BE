package com.team.dating_backend.chat.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.team.dating_backend.chat.service.ChatMessageRedisSubscriber;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

class ChatRealtimeRedisConfigurationTest {

    private final ChatRealtimeRedisConfiguration configuration = new ChatRealtimeRedisConfiguration();

    @Test
    void Redis_메시지를_지정한_스레드_풀에서_처리한다() {
        RedisConnectionFactory connectionFactory = org.mockito.Mockito.mock(
            RedisConnectionFactory.class);
        ChatMessageRedisSubscriber subscriber = org.mockito.Mockito.mock(
            ChatMessageRedisSubscriber.class);
        ThreadPoolTaskExecutor messageExecutor = configuration.chatRedisMessageExecutor();

        RedisMessageListenerContainer container = configuration.chatRedisMessageListenerContainer(
            connectionFactory, subscriber, messageExecutor, "chat:message-created:v1");

        assertThat(container.getConnectionFactory()).isSameAs(connectionFactory);
        assertThat(ReflectionTestUtils.getField(container, "taskExecutor"))
            .isSameAs(messageExecutor);
    }

    @Test
    void Redis_메시지는_단일_스레드에서_처리한다() {
        ThreadPoolTaskExecutor executor = configuration.chatRedisMessageExecutor();

        assertThat(executor.getCorePoolSize()).isEqualTo(1);
        assertThat(executor.getMaxPoolSize()).isEqualTo(1);
        assertThat(executor.getThreadNamePrefix()).isEqualTo("chat-redis-message-");
    }
}
