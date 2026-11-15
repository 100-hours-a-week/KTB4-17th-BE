package com.team.dating_backend.chat.config;

import com.team.dating_backend.chat.dto.event.ChatMessageCreatedKafkaEvent;
import com.team.dating_backend.common.kafka.KafkaListenerFactories;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "app.chat",
    name = {"runtime-enabled", "realtime-relay.enabled"},
    havingValue = "true"
)
public class ChatRealtimeRelayKafkaConfiguration {

    private static final long REDIS_RETRY_INTERVAL_MS = 1_000L;

    @Bean("chatRealtimeRelayKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> chatRealtimeRelayListenerFactory(
        ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
        ConsumerFactory<Object, Object> kafkaConsumerFactory,
        JsonMapper jsonMapper) {
        var factory = KafkaListenerFactories.jsonRecordFactory(configurer, kafkaConsumerFactory, jsonMapper,
            ChatMessageCreatedKafkaEvent.class, "earliest", Map.of(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
        factory.setCommonErrorHandler(new DefaultErrorHandler(
            new FixedBackOff(REDIS_RETRY_INTERVAL_MS, FixedBackOff.UNLIMITED_ATTEMPTS)));
        factory.getContainerProperties().setSyncCommits(false);
        return factory;
    }
}
