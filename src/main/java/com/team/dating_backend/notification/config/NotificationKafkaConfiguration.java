package com.team.dating_backend.notification.config;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.kafka.NotificationDeliveryFailureRecoverer;
import com.team.dating_backend.notification.kafka.NotificationDeliveryReadinessListener;
import com.team.dating_backend.notification.sse.NotificationDeliveryReadinessTracker;
import com.team.dating_backend.notification.sse.NotificationSseProcessIdentity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;
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
        JsonMapper jsonMapper,
        NotificationDeliveryProperties properties,
        NotificationSseProcessIdentity processIdentity,
        NotificationDeliveryFailureRecoverer recoverer,
        NotificationDeliveryReadinessListener readinessListener,
        NotificationDeliveryReadinessTracker readinessTracker) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = listenerContainerFactory(
            configurer, kafkaConsumerFactory, jsonMapper, NotificationDeliveryEvent.class, "latest",
            Map.of(
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, properties.getMaxPollRecords(),
                ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, properties.getMaxPollIntervalMs()));
        factory.setAutoStartup(properties.isEnabled());
        factory.setConcurrency(properties.getConcurrency());
        factory.setCommonErrorHandler(notificationDeliveryErrorHandler(recoverer, readinessTracker));
        factory.getContainerProperties().setConsumerRebalanceListener(readinessListener.createRebalanceListener());
        factory.getContainerProperties().setGroupId(processIdentity.getGroupId());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setSyncCommits(true);
        factory.getContainerProperties().setCheckDeserExWhenValueNull(true);
        return factory;
    }

    private CommonErrorHandler notificationDeliveryErrorHandler(
        NotificationDeliveryFailureRecoverer recoverer,
        NotificationDeliveryReadinessTracker readinessTracker) {
        var stoppingErrorHandler = new CommonContainerStoppingErrorHandler();
        return new CommonErrorHandler() {
            @Override
            public boolean handleOne(
                Exception thrownException,
                ConsumerRecord<?, ?> record,
                Consumer<?, ?> consumer,
                MessageListenerContainer container) {
                try {
                    recoverer.accept(record, thrownException);
                    return true;
                } catch (RuntimeException failure) {
                    readinessTracker.invalidateAll();
                    stoppingErrorHandler.handleRemaining(failure, List.of(record), consumer, container);
                    return false;
                } catch (Error error) {
                    readinessTracker.invalidateAll();
                    throw error;
                }
            }

            @Override
            public void handleRemaining(
                Exception thrownException,
                List<ConsumerRecord<?, ?>> records,
                Consumer<?, ?> consumer,
                MessageListenerContainer container) {
                readinessTracker.invalidateAll();
                stoppingErrorHandler.handleRemaining(thrownException, records, consumer, container);
            }

            @Override
            public void handleOtherException(
                Exception thrownException,
                Consumer<?, ?> consumer,
                MessageListenerContainer container,
                boolean batchListener) {
                readinessTracker.invalidateAll();
                stoppingErrorHandler.handleOtherException(thrownException, consumer, container, batchListener);
            }
        };
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
