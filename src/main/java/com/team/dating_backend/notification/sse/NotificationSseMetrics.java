package com.team.dating_backend.notification.sse;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationSseMetrics implements MeterBinder {

    private final NotificationDeliveryReadinessTracker readinessTracker;
    private final NotificationSseLifecycle lifecycle;
    private final NotificationEmitterRegistry emitterRegistry;

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("notification.delivery.ready", readinessTracker, tracker -> tracker.isReady() ? 1 : 0)
            .description("Whether all delivery partitions have confirmed starting positions")
            .register(registry);
        Gauge.builder("notification.sse.accepting", lifecycle, state -> state.isAcceptingSubscriptions() ? 1 : 0)
            .description("Whether this process accepts new notification SSE subscriptions")
            .register(registry);
        Gauge.builder("notification.sse.connections", emitterRegistry, emitters -> emitters.snapshotAll().size())
            .description("Current active notification SSE connections in this process")
            .register(registry);
    }
}
