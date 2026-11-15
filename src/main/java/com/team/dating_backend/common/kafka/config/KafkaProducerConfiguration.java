package com.team.dating_backend.common.kafka.config;

import java.util.Map;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.LongSerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class KafkaProducerConfiguration {

    @Bean
    public DefaultKafkaProducerFactoryCustomizer kafkaProducerFactoryCustomizer(JsonMapper jsonMapper) {
        return factory -> {
            factory.setKeySerializerSupplier(this::keySerializer);
            factory.setValueSerializerSupplier(() -> valueSerializer(jsonMapper));
        };
    }

    @SuppressWarnings("unchecked")
    private <T> Serializer<T> keySerializer() {
        return (Serializer<T>) new DelegatingByTypeSerializer(Map.of(
            Long.class, new LongSerializer(),
            String.class, new StringSerializer(),
            byte[].class, new ByteArraySerializer()));
    }

    @SuppressWarnings("unchecked")
    private <T> Serializer<T> valueSerializer(JsonMapper jsonMapper) {
        return (Serializer<T>) new DelegatingByTypeSerializer(Map.of(
            byte[].class, new ByteArraySerializer(),
            Object.class, new JacksonJsonSerializer<>(jsonMapper)), true);
    }
}
