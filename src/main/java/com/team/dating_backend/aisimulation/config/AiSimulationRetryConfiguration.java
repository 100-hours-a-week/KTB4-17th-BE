package com.team.dating_backend.aisimulation.config;

import com.team.dating_backend.aisimulation.kafka.AiSimulationKafkaListeners;
import com.team.dating_backend.aisimulation.kafka.AiSimulationRecoveryHandler;
import com.team.dating_backend.common.kafka.KafkaRetryPolicy;
import com.team.dating_backend.common.kafka.config.KafkaRetryInfrastructureConfiguration;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.retrytopic.DeadLetterPublishingRecovererFactory;

@Configuration(proxyBeanMethods = false)
@Import({KafkaRetryInfrastructureConfiguration.class, AiSimulationRecoveryHandler.class})
@RequiredArgsConstructor
public class AiSimulationRetryConfiguration implements KafkaRetryPolicy {

    private final AiSimulationKafkaProperties properties;
    private final AiSimulationRecoveryHandler recoveryHandler;

    @Override
    public Set<String> listenerIds() {
        return AiSimulationKafkaListeners.listenerIds();
    }

    @Override
    public long publishResultTimeoutMs() {
        return properties.getPublishResultTimeoutMs();
    }

    @Override
    public void customizeRecovererFactory(DeadLetterPublishingRecovererFactory factory) {
        factory.setDeadLetterPublisherCreator(recoveryHandler::createRecoverer);
    }
}
