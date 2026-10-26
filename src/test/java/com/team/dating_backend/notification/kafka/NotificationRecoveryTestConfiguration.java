package com.team.dating_backend.notification.kafka;

import static org.mockito.Mockito.mock;

import com.team.dating_backend.notification.alert.DiscordWebhookClient;
import com.team.dating_backend.notification.alert.NotificationAlertDispatcher;
import com.team.dating_backend.notification.alert.NotificationFailureReporter;
import com.team.dating_backend.notification.config.NotificationAlertProperties;
import com.team.dating_backend.notification.config.NotificationDeliveryProperties;
import com.team.dating_backend.notification.config.NotificationKafkaProperties;
import com.team.dating_backend.notification.sse.NotificationSseProcessIdentity;
import com.team.dating_backend.notification.sse.NotificationDeliveryReadinessTracker;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties({NotificationKafkaProperties.class, NotificationDeliveryProperties.class})
@Import(
    {NotificationRecoveryReporter.class, NotificationFailureReporter.class,
        NotificationDeliveryFailureRecoverer.class, NotificationDeliveryReadinessTracker.class,
        NotificationDeliveryReadinessListener.class}
)
public class NotificationRecoveryTestConfiguration {

    @Bean
    public NotificationSseProcessIdentity notificationSseProcessIdentity(NotificationDeliveryProperties properties) {
        return new NotificationSseProcessIdentity(properties);
    }

    @Bean
    public MeterRegistry recoveryMeterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    public DiscordWebhookClient recoveryWebhookClient() {
        return mock(DiscordWebhookClient.class);
    }

    @Bean
    public NotificationAlertDispatcher recoveryAlertDispatcher(DiscordWebhookClient client, MeterRegistry registry) {
        var properties = new NotificationAlertProperties();
        properties.setEnabled(true);
        return new NotificationAlertDispatcher(Runnable::run, client, properties, registry);
    }
}
