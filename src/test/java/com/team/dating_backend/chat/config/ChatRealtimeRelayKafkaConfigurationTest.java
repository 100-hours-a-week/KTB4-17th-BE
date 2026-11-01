package com.team.dating_backend.chat.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import tools.jackson.databind.json.JsonMapper;

class ChatRealtimeRelayKafkaConfigurationTest {

    @Test
    void Redis_발행_후_Offset을_비동기로_커밋한다() {
        ConcurrentKafkaListenerContainerFactoryConfigurer configurer = org.mockito.Mockito.mock(
            ConcurrentKafkaListenerContainerFactoryConfigurer.class);
        @SuppressWarnings("unchecked")
        ConsumerFactory<Object, Object> baseConsumerFactory = org.mockito.Mockito.mock(
            ConsumerFactory.class);
        given(baseConsumerFactory.getConfigurationProperties()).willReturn(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:29092"));
        doAnswer(invocation -> {
            ConcurrentKafkaListenerContainerFactory<Object, Object> factory = invocation.getArgument(0);
            ConsumerFactory<Object, Object> consumerFactory = invocation.getArgument(1);
            factory.setConsumerFactory(consumerFactory);
            return null;
        }).when(configurer).configure(any(), any());

        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ChatRealtimeRelayKafkaConfiguration()
            .chatRealtimeRelayListenerFactory(
                configurer, baseConsumerFactory, new JsonMapper());

        assertThat(factory.getContainerProperties().getAckMode())
            .isEqualTo(ContainerProperties.AckMode.RECORD);
        assertThat(factory.getContainerProperties().isSyncCommits()).isFalse();
        assertThat(factory.getConsumerFactory().getConfigurationProperties())
            .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
            .containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, LongDeserializer.class)
            .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        assertThat(factory.createContainer("chat.message-created.v1").getCommonErrorHandler())
            .isInstanceOf(DefaultErrorHandler.class);
    }
}
