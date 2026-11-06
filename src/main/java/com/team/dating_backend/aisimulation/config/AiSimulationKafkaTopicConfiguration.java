package com.team.dating_backend.aisimulation.config;

import com.team.dating_backend.aisimulation.enums.AiSimulationErrorCode;
import com.team.dating_backend.aisimulation.exception.AiSimulationBusinessException;
import com.team.dating_backend.aisimulation.kafka.AiSimulationKafkaListeners;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration(proxyBeanMethods = false)
public class AiSimulationKafkaTopicConfiguration {

    @Bean
    public KafkaAdmin.NewTopics aiSimulationKafkaTopics(AiSimulationKafkaProperties properties,
        Environment environment) {
        var names = new LinkedHashSet<String>();
        names.add(properties.getRequestedTopic());
        names.add(properties.getCompletedTopic());
        for (var definition : AiSimulationKafkaListeners.definitions()) {
            var listener = AnnotatedElementUtils.findMergedAnnotation(definition, KafkaListener.class);
            var retry = AnnotatedElementUtils.findMergedAnnotation(definition, RetryableTopic.class);
            String original = environment.resolveRequiredPlaceholders(listener.topics()[0]);
            if (!names.add(original + retry.retryTopicSuffix()) || !names.add(original + retry.dltTopicSuffix())) {
                throw invalidConfiguration();
            }
        }
        if (names.size() != 12 || names.stream().anyMatch(name -> name.length() > 249)
            || properties.getMinInSyncReplicas() > properties.getReplicationFactor()) {
            throw invalidConfiguration();
        }
        var configs = new HashMap<String, String>();
        configs.put(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(properties.getMinInSyncReplicas()));
        configs.put(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE);
        if (properties.getRetentionMs() != null) {
            configs.put(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(properties.getRetentionMs()));
        }
        var topics = new ArrayList<NewTopic>();
        names.forEach(name -> topics.add(new NewTopic(name, properties.getPartitions(),
            (short) properties.getReplicationFactor()).configs(configs)));
        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }

    private AiSimulationBusinessException invalidConfiguration() {
        return new AiSimulationBusinessException(AiSimulationErrorCode.SIMULATION_KAFKA_CONFIGURATION_INVALID);
    }
}
