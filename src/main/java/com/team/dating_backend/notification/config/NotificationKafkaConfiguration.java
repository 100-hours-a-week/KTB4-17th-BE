package com.team.dating_backend.notification.config;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class NotificationKafkaConfiguration {

    @Bean
    public DefaultKafkaProducerFactoryCustomizer notificationKafkaProducerFactoryCustomizer(
        JsonMapper jsonMapper) {
        return factory -> factory.setValueSerializerSupplier(
            () -> notificationValueSerializer(jsonMapper));
    }

    @SuppressWarnings("unchecked")
    private <T> Serializer<T> notificationValueSerializer(JsonMapper jsonMapper) {
        return (Serializer<T>) new DelegatingByTypeSerializer(Map.of(
            byte[].class, new ByteArraySerializer(),
            Object.class, new JacksonJsonSerializer<>(jsonMapper)), true);
    }

    @Bean("notificationCreateRequestedKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> notificationCreateRequestedListenerFactory(
        ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
        ConsumerFactory<Object, Object> kafkaConsumerFactory,
        JsonMapper jsonMapper,
        NotificationCreationProperties properties) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = listenerContainerFactory(
            configurer, kafkaConsumerFactory, jsonMapper, NotificationCreateRequested.class, "earliest",
            Map.of(
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, properties.getMaxPollRecords(),
                ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, properties.getMaxPollIntervalMs()));
        factory.setAutoStartup(properties.isEnabled());
        factory.setConcurrency(properties.getConcurrency());
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        factory.getContainerProperties().setGroupId(properties.getGroupId());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setSyncCommits(true);
        factory.getContainerProperties().setCheckDeserExWhenValueNull(true);
        return factory;
    }

    @Bean("notificationDeliveryKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> notificationDeliveryListenerFactory(
        ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
        ConsumerFactory<Object, Object> kafkaConsumerFactory,
        JsonMapper jsonMapper) {
        return listenerContainerFactory(
            configurer, kafkaConsumerFactory, jsonMapper, NotificationDeliveryEvent.class, "latest", Map.of());
    }

    private ConcurrentKafkaListenerContainerFactory<Object, Object> listenerContainerFactory(
        ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
        ConsumerFactory<Object, Object> kafkaConsumerFactory,
        JsonMapper jsonMapper,
        Class<?> payloadType,
        String autoOffsetReset,
        Map<String, Object> overrides) {
        Map<String, Object> consumerProperties = new HashMap<>(kafkaConsumerFactory.getConfigurationProperties());
        consumerProperties.putAll(overrides);
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        consumerProperties.put(JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, payloadType);
        consumerProperties.put(JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        DefaultKafkaConsumerFactory<Object, Object> consumerFactory = new DefaultKafkaConsumerFactory<>(
            consumerProperties,
            () -> null,
            () -> new ErrorHandlingDeserializer<>(new JacksonJsonDeserializer<>(jsonMapper)));
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setBatchListener(false);
        return factory;
    }
}
