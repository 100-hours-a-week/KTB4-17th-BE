package com.team.dating_backend.notification.sse;

import com.team.dating_backend.common.enums.CommonErrorCode;
import com.team.dating_backend.notification.config.NotificationDeliveryProperties;
import com.team.dating_backend.notification.enums.NotificationErrorCode;
import com.team.dating_backend.notification.exception.NotificationBusinessException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

@Component
public class NotificationSseLifecycle {

    private static final String DELIVERY_LISTENER_ID = "notificationDeliveryConsumer";

    private final boolean deliveryEnabled;
    private final NotificationDeliveryReadinessTracker readinessTracker;
    private final NotificationEmitterRegistry emitterRegistry;
    private final ObjectProvider<KafkaListenerEndpointRegistry> listenerRegistryProvider;
    private boolean draining;

    public NotificationSseLifecycle(
        NotificationDeliveryProperties properties,
        NotificationDeliveryReadinessTracker readinessTracker,
        NotificationEmitterRegistry emitterRegistry,
        ObjectProvider<KafkaListenerEndpointRegistry> listenerRegistryProvider) {
        if (properties == null || readinessTracker == null || emitterRegistry == null
            || listenerRegistryProvider == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        this.deliveryEnabled = properties.isEnabled();
        this.readinessTracker = readinessTracker;
        this.emitterRegistry = emitterRegistry;
        this.listenerRegistryProvider = listenerRegistryProvider;
    }

    public boolean isAcceptingSubscriptions() {
        synchronized (readinessTracker) {
            return acceptingSubscriptions();
        }
    }

    public void assertAcceptingSubscriptions() {
        synchronized (readinessTracker) {
            requireAcceptingSubscriptions();
        }
    }

    public boolean registerIfAccepting(NotificationSseConnection connection) {
        if (connection == null) {
            throw new NotificationBusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        synchronized (readinessTracker) {
            requireAcceptingSubscriptions();
            return emitterRegistry.register(connection);
        }
    }

    public void beginDrain() {
        synchronized (readinessTracker) {
            draining = true;
        }
    }

    private void requireAcceptingSubscriptions() {
        if (!acceptingSubscriptions()) {
            throw new NotificationBusinessException(NotificationErrorCode.NOTIFICATION_STREAM_NOT_READY);
        }
    }

    private boolean acceptingSubscriptions() {
        if (draining) {
            return false;
        }
        if (!deliveryEnabled) {
            return true;
        }

        KafkaListenerEndpointRegistry registry = listenerRegistryProvider.getIfAvailable();
        MessageListenerContainer container = registry == null
            ? null
            : registry.getListenerContainer(DELIVERY_LISTENER_ID);
        return container != null && container.isRunning() && !container.isPauseRequested()
            && readinessTracker.isReady();
    }
}
