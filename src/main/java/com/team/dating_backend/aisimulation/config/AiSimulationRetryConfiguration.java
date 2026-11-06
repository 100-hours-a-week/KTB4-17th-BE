package com.team.dating_backend.aisimulation.config;

import com.team.dating_backend.aisimulation.kafka.AiSimulationKafkaListeners;
import com.team.dating_backend.common.kafka.KafkaRetryPolicy;
import com.team.dating_backend.common.kafka.config.KafkaRetryInfrastructureConfiguration;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import(KafkaRetryInfrastructureConfiguration.class)
@RequiredArgsConstructor
public class AiSimulationRetryConfiguration implements KafkaRetryPolicy {

    private final AiSimulationKafkaProperties properties;

    @Override
    public Set<String> listenerIds() {
        return AiSimulationKafkaListeners.listenerIds();
    }

    @Override
    public long publishResultTimeoutMs() {
        return properties.getPublishResultTimeoutMs();
    }
}
