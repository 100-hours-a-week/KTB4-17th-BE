package com.team.dating_backend.common.kafka;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import tools.jackson.databind.json.JsonMapper;

public final class KafkaListenerFactories {

    private KafkaListenerFactories() {}

    public static ConcurrentKafkaListenerContainerFactory<Object, Object> jsonRecordFactory(
        ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
        ConsumerFactory<Object, Object> baseConsumerFactory,
        JsonMapper jsonMapper,
        Class<?> eventType,
        String autoOffsetReset,
        Map<String, Object> overrides) {
        var properties = new HashMap<>(baseConsumerFactory.getConfigurationProperties());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, LongDeserializer.class);
        properties.putAll(overrides);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        properties.put(JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, eventType);
        properties.put(JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        var consumerFactory = new DefaultKafkaConsumerFactory<Object, Object>(properties,
            () -> properties.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG) == ErrorHandlingDeserializer.class
                ? new ErrorHandlingDeserializer<>()
                : null,
            () -> new ErrorHandlingDeserializer<>(new JacksonJsonDeserializer<>(jsonMapper)));
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumerFactory);
        factory.setBatchListener(false);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setSyncCommits(true);
        factory.getContainerProperties().setCheckDeserExWhenValueNull(true);
        return factory;
    }
}
