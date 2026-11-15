package com.team.dating_backend.aisimulation.kafka;

import com.team.dating_backend.aisimulation.exception.AiSimulationRetryableProcessingException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;
import java.util.List;
import org.springframework.core.annotation.AliasFor;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;

public final class AiSimulationKafkaListeners {

    public static final String GENERATION = "aiSimulationGeneration";
    public static final String REQUEST_NOTIFICATION = "aiSimulationRequestNotification";
    public static final String RECOMMENDATION = "aiSimulationRecommendation";
    public static final String REQUESTER_NOTIFICATION = "aiSimulationRequesterNotification";
    public static final String TARGET_NOTIFICATION = "aiSimulationTargetNotification";

    private AiSimulationKafkaListeners() {}

    public static List<Class<? extends Annotation>> definitions() {
        return List.of(Generation.class, RequestNotification.class, Recommendation.class,
            RequesterNotification.class, TargetNotification.class);
    }

    public static Set<String> listenerIds() {
        return Set.of(GENERATION, REQUEST_NOTIFICATION, RECOMMENDATION, REQUESTER_NOTIFICATION,
            TARGET_NOTIFICATION);
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @KafkaListener(
        id = GENERATION,
        topics = "${app.ai-simulation.kafka.requested-topic:local-ai-simulation-requested}",
        groupId = "${app.ai-simulation.kafka.generation.group-id:local-ai-simulation-generation}",
        containerFactory = "aiSimulationGenerationKafkaListenerContainerFactory"
    )
    @SimulationRetry(retryTopicSuffix = "-generation-retry", dltTopicSuffix = "-generation-dlt")
    public @interface Generation {}

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @KafkaListener(
        id = REQUEST_NOTIFICATION,
        topics = "${app.ai-simulation.kafka.requested-topic:local-ai-simulation-requested}",
        groupId = "${app.ai-simulation.kafka.request-notification.group-id:local-ai-simulation-request-notification}",
        containerFactory = "aiSimulationRequestNotificationKafkaListenerContainerFactory"
    )
    @SimulationRetry(retryTopicSuffix = "-request-notification-retry", dltTopicSuffix = "-request-notification-dlt")
    public @interface RequestNotification {}

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @KafkaListener(
        id = RECOMMENDATION,
        topics = "${app.ai-simulation.kafka.requested-topic:local-ai-simulation-requested}",
        groupId = "${app.ai-simulation.kafka.recommendation.group-id:local-ai-simulation-recommendation}",
        containerFactory = "aiSimulationRecommendationKafkaListenerContainerFactory"
    )
    @SimulationRetry(retryTopicSuffix = "-recommendation-retry", dltTopicSuffix = "-recommendation-dlt")
    public @interface Recommendation {}

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @KafkaListener(
        id = REQUESTER_NOTIFICATION,
        topics = "${app.ai-simulation.kafka.completed-topic:local-ai-simulation-completed}",
        groupId = "${app.ai-simulation.kafka.requester-notification.group-id:local-ai-simulation-requester-notification}",
        containerFactory = "aiSimulationRequesterNotificationKafkaListenerContainerFactory"
    )
    @SimulationRetry(retryTopicSuffix = "-requester-notification-retry", dltTopicSuffix = "-requester-notification-dlt")
    public @interface RequesterNotification {}

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @KafkaListener(
        id = TARGET_NOTIFICATION,
        topics = "${app.ai-simulation.kafka.completed-topic:local-ai-simulation-completed}",
        groupId = "${app.ai-simulation.kafka.target-notification.group-id:local-ai-simulation-target-notification}",
        containerFactory = "aiSimulationTargetNotificationKafkaListenerContainerFactory"
    )
    @SimulationRetry(retryTopicSuffix = "-target-notification-retry", dltTopicSuffix = "-target-notification-dlt")
    public @interface TargetNotification {}
}

@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
@RetryableTopic(
    attempts = "${app.ai-simulation.kafka.retry.max-attempts:4}",
    backOff = @BackOff(delayString = "${app.ai-simulation.kafka.retry.delay-ms:5000}"),
    concurrency = "${app.ai-simulation.kafka.retry.concurrency:1}",
    kafkaTemplate = "kafkaTemplate",
    include = AiSimulationRetryableProcessingException.class,
    traversingCauses = "true",
    autoCreateTopics = "false",
    numPartitions = "${app.ai-simulation.kafka.partitions:4}",
    replicationFactor = "${app.ai-simulation.kafka.replication-factor:3}",
    autoStartDltHandler = "false",
    dltStrategy = DltStrategy.FAIL_ON_ERROR,
    sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC
)
@interface SimulationRetry {

    @AliasFor(annotation = RetryableTopic.class, attribute = "retryTopicSuffix")
    String retryTopicSuffix();

    @AliasFor(annotation = RetryableTopic.class, attribute = "dltTopicSuffix")
    String dltTopicSuffix();
}
