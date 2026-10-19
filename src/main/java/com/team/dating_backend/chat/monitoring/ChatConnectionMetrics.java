package com.team.dating_backend.chat.monitoring;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.function.ToDoubleFunction;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.broker.AbstractBrokerMessageHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.config.WebSocketMessageBrokerStats;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;

// 기존 서버에 남은 연결과 전환 중 비정상 종료를 확인하기 위한 지표다.
@Component
public class ChatConnectionMetrics implements MeterBinder {

    private final ObjectProvider<WebSocketMessageBrokerStats> brokerStats;
    private final ObjectProvider<AbstractBrokerMessageHandler> brokerHandlers;

    public ChatConnectionMetrics(
        ObjectProvider<WebSocketMessageBrokerStats> brokerStats,
        ObjectProvider<AbstractBrokerMessageHandler> brokerHandlers) {
        this.brokerStats = brokerStats;
        this.brokerHandlers = brokerHandlers;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        // 초기화 중 참조를 피하도록 지표 수집 시점에 WebSocket 구성 요소를 조회한다.
        Gauge.builder("chat.websocket.sessions.active", this,
            metrics -> metrics.sessionValue(stats -> stats.getWebSocketSessions()
                + stats.getHttpStreamingSessions() + stats.getHttpPollingSessions()))
            .description("Open WebSocket/SockJS sessions, including sessions before STOMP CONNECT")
            .register(registry);
        FunctionCounter.builder("chat.websocket.sessions.total", this,
            metrics -> metrics.sessionValue(SubProtocolWebSocketHandler.Stats::getTotalSessions))
            .description("WebSocket/SockJS sessions opened since this process started")
            .register(registry);
        registerAbnormal(registry, "limit_exceeded",
            SubProtocolWebSocketHandler.Stats::getLimitExceededSessions);
        registerAbnormal(registry, "no_messages_received",
            SubProtocolWebSocketHandler.Stats::getNoMessagesReceivedSessions);
        registerAbnormal(registry, "transport_error",
            SubProtocolWebSocketHandler.Stats::getTransportErrorSessions);
        Gauge.builder("chat.broker.available", this,
            metrics -> metrics.brokerHandlers.stream()
                .anyMatch(AbstractBrokerMessageHandler::isBrokerAvailable) ? 1 : 0)
            .description("Current Spring broker availability; 1 is available, 0 is unavailable")
            .register(registry);
    }

    private void registerAbnormal(MeterRegistry registry, String reason,
        ToDoubleFunction<SubProtocolWebSocketHandler.Stats> value) {
        FunctionCounter.builder("chat.websocket.sessions.abnormal", this,
            metrics -> metrics.sessionValue(value))
            .tag("reason", reason)
            .description("Spring WebSocket error diagnostics; not every unexpected client disconnect")
            .register(registry);
    }

    private double sessionValue(ToDoubleFunction<SubProtocolWebSocketHandler.Stats> value) {
        WebSocketMessageBrokerStats stats = brokerStats.getIfAvailable();
        SubProtocolWebSocketHandler.Stats sessions = stats == null
            ? null
            : stats.getWebSocketSessionStats();
        // 구성 요소 조회 실패를 연결 종료 완료로 판단하지 않는다.
        return sessions == null ? Double.NaN : value.applyAsDouble(sessions);
    }
}
