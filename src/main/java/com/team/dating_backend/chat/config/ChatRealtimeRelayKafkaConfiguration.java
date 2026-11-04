package com.team.dating_backend.chat.config;

import com.team.dating_backend.chat.dto.event.ChatMessageCreatedKafkaEvent;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
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
        Map<String, Object> consumerProperties = new HashMap<>(
            kafkaConsumerFactory.getConfigurationProperties());
        consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            LongDeserializer.class);
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProperties.put(JacksonJsonDeserializer.VALUE_DEFAULT_TYPE,
            ChatMessageCreatedKafkaEvent.class);
        consumerProperties.put(JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        DefaultKafkaConsumerFactory<Object, Object> consumerFactory = new DefaultKafkaConsumerFactory<>(
            consumerProperties,
            () -> null,
            () -> new ErrorHandlingDeserializer<>(new JacksonJsonDeserializer<>(jsonMapper)));
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setBatchListener(false);
        factory.setCommonErrorHandler(new DefaultErrorHandler(
            new FixedBackOff(REDIS_RETRY_INTERVAL_MS, FixedBackOff.UNLIMITED_ATTEMPTS)));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setSyncCommits(false);
        factory.getContainerProperties().setCheckDeserExWhenValueNull(true);
        return factory;
    }
}
