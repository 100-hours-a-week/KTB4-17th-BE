package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.config.ChatRabbitProperties;
import com.team.dating_backend.chat.websocket.RabbitUserDestinations;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class RabbitUserEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final ChatRabbitProperties properties;
    private final RabbitUserDestinations destinations;

    public void publish(String userId, String destination, Object event) {
        MessageProperties headers = new MessageProperties();
        headers.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        headers.setContentEncoding("UTF-8");
        headers.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        Message message = new Message(objectMapper.writeValueAsBytes(event), headers);
        CorrelationData correlation = new CorrelationData();
        String routingKey = destinations.routingKey(userId, destination);
        try {
            rabbitTemplate.send(properties.getExchange(), routingKey, message, correlation);
            // 브로커 수신 확인 후에만 발행 성공으로 처리한다. 사용자 수신 확인은 아니다.
            CorrelationData.Confirm confirm = correlation.getFuture()
                .get(properties.getConfirmTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                throw new IllegalStateException("RabbitMQ rejected the event: " + confirm.reason());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RabbitMQ confirmation interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("RabbitMQ confirmation failed", exception);
        }
    }
}
