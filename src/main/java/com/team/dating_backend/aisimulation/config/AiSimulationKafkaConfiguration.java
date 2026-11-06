package com.team.dating_backend.aisimulation.config;

import com.team.dating_backend.aisimulation.dto.event.AiSimulationCompletedEvent;
import com.team.dating_backend.aisimulation.dto.event.AiSimulationRequestedEvent;
import java.util.HashMap;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
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
        var consumerProperties = new HashMap<>(kafkaConsumerFactory.getConfigurationProperties());
        consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerProperties.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProperties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, poll.getMaxPollRecords());
        consumerProperties.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, poll.getMaxPollIntervalMs());
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        consumerProperties.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, LongDeserializer.class);
        consumerProperties.put(JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, eventType);
        consumerProperties.put(JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        var consumerFactory = new DefaultKafkaConsumerFactory<Object, Object>(consumerProperties,
            ErrorHandlingDeserializer::new,
            () -> new ErrorHandlingDeserializer<>(new JacksonJsonDeserializer<>(jsonMapper)));
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, consumerFactory);
        factory.setBatchListener(false);
        factory.setAutoStartup(group.isEnabled());
        factory.setConcurrency(properties.getConcurrency());
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        factory.getContainerProperties().setGroupId(group.getGroupId());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setSyncCommits(true);
        factory.getContainerProperties().setCheckDeserExWhenKeyNull(true);
        factory.getContainerProperties().setCheckDeserExWhenValueNull(true);
        return factory;
    }
}
