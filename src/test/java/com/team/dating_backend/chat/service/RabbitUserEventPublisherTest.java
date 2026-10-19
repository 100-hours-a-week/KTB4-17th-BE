package com.team.dating_backend.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.team.dating_backend.chat.config.ChatRabbitProperties;
import com.team.dating_backend.chat.websocket.RabbitUserDestinations;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.ObjectMapper;

class RabbitUserEventPublisherTest {

    private RabbitTemplate template;
    private RabbitUserEventPublisher publisher;

    @BeforeEach
    void setUp() {
        template = mock(RabbitTemplate.class);
        ChatRabbitProperties properties = new ChatRabbitProperties();
        properties.setConfirmTimeoutMs(20);
        publisher = new RabbitUserEventPublisher(template, new ObjectMapper(), properties,
            new RabbitUserDestinations(properties));
    }

    @Test
    void RabbitMQ가_확인한_JSON_이벤트를_사용자별_라우팅키로_보낸다() {
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            assertThat(new String(message.getBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"text\":\"메시지\"}");
            assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(eq("ecs.chat.events"), eq("user.42.chat-messages"),
            any(Message.class), any(CorrelationData.class));

        publisher.publish("42", "/queue/chat-messages", Map.of("text", "메시지"));
    }

    @Test
    void 브로커가_거절하면_성공으로_처리하지_않는다() {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "unavailable"));
            return null;
        }).when(template).send(any(String.class), any(String.class),
            any(Message.class), any(CorrelationData.class));

        assertThatThrownBy(() -> publisher.publish("42", "/queue/chat-messages", Map.of()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("rejected");
    }

    @Test
    void 확인_시간이_초과되면_Outbox가_재시도할_수_있도록_실패한다() {
        assertThatThrownBy(() -> publisher.publish("42", "/queue/chat-messages", Map.of()))
            .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(TimeoutException.class);
    }
}
