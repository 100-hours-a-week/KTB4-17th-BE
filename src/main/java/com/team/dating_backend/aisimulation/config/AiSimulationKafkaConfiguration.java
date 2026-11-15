package com.team.dating_backend.aisimulation.config;

import com.team.dating_backend.aisimulation.dto.event.AiSimulationCompletedEvent;
import com.team.dating_backend.aisimulation.dto.event.AiSimulationRequestedEvent;
import java.util.Map;
import com.team.dating_backend.common.kafka.KafkaListenerFactories;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class AiSimulationKafkaConfiguration {

    private final ConcurrentKafkaListenerContainerFactoryConfigurer configurer;
    private final ConsumerFactory<Object, Object> kafkaConsumerFactory;
    private final JsonMapper jsonMapper;
    private final AiSimulationKafkaProperties properties;

    @Bean("aiSimulationGenerationKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> generationListenerFactory() {
        return listenerFactory(AiSimulationRequestedEvent.class, properties.getGeneration(),
            properties.getGenerationPoll());
    }

    @Bean("aiSimulationRequestNotificationKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> requestNotificationListenerFactory() {
        return listenerFactory(AiSimulationRequestedEvent.class, properties.getRequestNotification(),
            properties.getSideEffectPoll());
    }

    @Bean("aiSimulationRecommendationKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> recommendationListenerFactory() {
        return listenerFactory(AiSimulationRequestedEvent.class, properties.getRecommendation(),
            properties.getSideEffectPoll());
    }

    @Bean("aiSimulationRequesterNotificationKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> requesterNotificationListenerFactory() {
        return listenerFactory(AiSimulationCompletedEvent.class, properties.getRequesterNotification(),
            properties.getSideEffectPoll());
    }

    @Bean("aiSimulationTargetNotificationKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> targetNotificationListenerFactory() {
        return listenerFactory(AiSimulationCompletedEvent.class, properties.getTargetNotification(),
            properties.getSideEffectPoll());
    }

    private ConcurrentKafkaListenerContainerFactory<Object, Object> listenerFactory(
        Class<?> eventType,
        AiSimulationKafkaProperties.ConsumerGroup group,
        AiSimulationKafkaProperties.Poll poll) {
        var factory = KafkaListenerFactories.jsonRecordFactory(configurer, kafkaConsumerFactory, jsonMapper,
            eventType, "earliest", Map.of(
                ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false,
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, poll.getMaxPollRecords(),
                ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, poll.getMaxPollIntervalMs(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class,
                ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, LongDeserializer.class));
        factory.setAutoStartup(group.isEnabled());
        factory.setConcurrency(properties.getConcurrency());
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        factory.getContainerProperties().setGroupId(group.getGroupId());
        factory.getContainerProperties().setCheckDeserExWhenKeyNull(true);
        return factory;
    }
}
