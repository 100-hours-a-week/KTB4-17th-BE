package com.team.dating_backend.aisimulation.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.ai-simulation.kafka")
public class AiSimulationKafkaProperties {

    private static final String TOPIC_NAME_PATTERN = "(?!\\.{1,2}$)(?!__)[a-zA-Z0-9._-]+";

    @NotBlank
    @Size(max = 249)
    @Pattern(regexp = TOPIC_NAME_PATTERN)
    private String requestedTopic = "local-ai-simulation-requested";

    @NotBlank
    @Size(max = 249)
    @Pattern(regexp = TOPIC_NAME_PATTERN)
    private String completedTopic = "local-ai-simulation-completed";

    @Positive
    private int partitions = 4;

    @Positive
    private int concurrency = 2;

    @Positive
    private long publishResultTimeoutMs = 15_000L;

    @Valid
    private final ConsumerGroup generation = new ConsumerGroup("local-ai-simulation-generation");

    @Valid
    private final ConsumerGroup requestNotification = new ConsumerGroup("local-ai-simulation-request-notification");

    @Valid
    private final ConsumerGroup recommendation = new ConsumerGroup("local-ai-simulation-recommendation");

    @Valid
    private final ConsumerGroup requesterNotification = new ConsumerGroup("local-ai-simulation-requester-notification");

    @Valid
    private final ConsumerGroup targetNotification = new ConsumerGroup("local-ai-simulation-target-notification");

    @Valid
    private final Poll generationPoll = new Poll(180_000);

    @Valid
    private final Poll sideEffectPoll = new Poll(300_000);

    @Valid
    private final Retry retry = new Retry();

    @AssertTrue(message = "Requested and completed topics must be different")
    public boolean isTopicsDistinct() {
        return requestedTopic == null || !requestedTopic.equals(completedTopic);
    }

    @AssertTrue(message = "Simulation consumer groups must be different")
    public boolean isConsumerGroupsDistinct() {
        return Stream.of(generation, requestNotification, recommendation, requesterNotification, targetNotification)
            .map(ConsumerGroup::getGroupId).distinct().count() == 5;
    }

    @Getter
    @Setter
    public static class ConsumerGroup {

        private boolean enabled = false;

        @NotBlank
        private String groupId;

        public ConsumerGroup(String groupId) {
            this.groupId = groupId;
        }
    }

    @Getter
    @Setter
    public static class Poll {

        @Positive
        private int maxPollRecords = 1;

        @Positive
        private int maxPollIntervalMs;

        public Poll(int maxPollIntervalMs) {
            this.maxPollIntervalMs = maxPollIntervalMs;
        }
    }

    @Getter
    @Setter
    public static class Retry {

        @Positive
        private int maxAttempts = 4;

        @Positive
        private long delayMs = 5_000L;

        @Positive
        private int concurrency = 1;
    }
}
