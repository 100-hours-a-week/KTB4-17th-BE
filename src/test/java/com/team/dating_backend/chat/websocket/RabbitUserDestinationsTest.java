package com.team.dating_backend.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.team.dating_backend.chat.config.ChatRabbitProperties;
import org.junit.jupiter.api.Test;

class RabbitUserDestinationsTest {

    private final RabbitUserDestinations destinations = new RabbitUserDestinations(new ChatRabbitProperties());

    @Test
    void 기존_클라이언트_경로와_사용자별_RabbitMQ_경로를_변환한다() {
        for (String client : RabbitUserDestinations.USER_SUBSCRIPTIONS) {
            String broker = destinations.brokerDestination("42", client);
            assertThat(broker).startsWith("/exchange/ecs.chat.events/user.42.");
            assertThat(destinations.clientDestination(broker)).isEqualTo(client);
        }
    }

    @Test
    void 와일드카드와_허용되지_않은_이벤트는_발행하지_않는다() {
        assertThatThrownBy(() -> destinations.routingKey("*", "/queue/chat-messages"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> destinations.routingKey("42", "/queue/other"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
