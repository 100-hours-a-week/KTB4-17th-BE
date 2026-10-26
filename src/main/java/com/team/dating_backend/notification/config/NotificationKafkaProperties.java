package com.team.dating_backend.notification.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.notification.kafka")
public class NotificationKafkaProperties {

    private static final String TOPIC_NAME_PATTERN = "(?!\\.{1,2}$)(?!__)[a-zA-Z0-9._-]+";

    @NotBlank
    @Size(max = 249)
    @Pattern(regexp = TOPIC_NAME_PATTERN)
    private String createRequestedTopic;

    @NotBlank
    @Size(max = 249)
    @Pattern(regexp = TOPIC_NAME_PATTERN)
    private String deliveryTopic;

    @AssertTrue(message = "Creation and delivery topics must be different")
    public boolean isTopicsDistinct() {
        return createRequestedTopic == null || !createRequestedTopic.equals(deliveryTopic);
    }
}
