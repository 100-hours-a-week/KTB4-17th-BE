package com.team.dating_backend.notification.config;

import com.team.dating_backend.chat.dto.event.ChatMessageCreatedKafkaEvent;
import com.team.dating_backend.common.kafka.KafkaListenerFactories;
import com.team.dating_backend.common.kafka.config.KafkaProducerConfiguration;
import com.team.dating_backend.notification.dto.event.NotificationDeliveryEvent;
import com.team.dating_backend.notification.kafka.NotificationDeliveryFailureRecoverer;
import com.team.dating_backend.notification.kafka.NotificationDeliveryReadinessListener;
import com.team.dating_backend.notification.sse.NotificationDeliveryReadinessTracker;
import com.team.dating_backend.notification.sse.NotificationSseProcessIdentity;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@Import(KafkaProducerConfiguration.class)
public class NotificationKafkaConfiguration {

    private static final long CHAT_MESSAGE_RETRY_INTERVAL_MS = 1_000L;

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
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = KafkaListenerFactories.jsonRecordFactory(
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
        return factory;
    }

    @Bean("notificationChatMessageKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> notificationChatMessageListenerFactory(
        ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
        ConsumerFactory<Object, Object> kafkaConsumerFactory,
        JsonMapper jsonMapper) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = KafkaListenerFactories.jsonRecordFactory(
            configurer, kafkaConsumerFactory, jsonMapper, ChatMessageCreatedKafkaEvent.class, "earliest",
            Map.of(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
        factory.setCommonErrorHandler(new DefaultErrorHandler(
            new FixedBackOff(CHAT_MESSAGE_RETRY_INTERVAL_MS, FixedBackOff.UNLIMITED_ATTEMPTS)));
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

}
