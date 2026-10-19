package com.team.dating_backend.chat.websocket;

import com.team.dating_backend.chat.config.ChatRabbitProperties;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RabbitUserDestinations {

    public static final Set<String> USER_SUBSCRIPTIONS = Set.of(
        "/user/queue/chat-messages",
        "/user/queue/chat-read-receipts",
        "/user/queue/chat-room-updates",
        "/user/queue/ai-practice");

    private final ChatRabbitProperties properties;

    // 접속 서버와 무관하게 같은 사용자의 이벤트가 같은 라우팅 키를 쓰도록 한다.
    public String routingKey(String userId, String destination) {
        if (userId == null || !userId.matches("[1-9][0-9]*")
            || !USER_SUBSCRIPTIONS.contains("/user" + destination)) {
            throw new IllegalArgumentException("Invalid user event destination");
        }
        return "user." + userId + "." + destination.substring("/queue/".length());
    }

    public String brokerDestination(String userId, String clientDestination) {
        return "/exchange/" + properties.getExchange() + "/"
            + routingKey(userId, clientDestination.substring("/user".length()));
    }

    public String clientDestination(String brokerDestination) {
        String prefix = "/exchange/" + properties.getExchange() + "/user.";
        if (brokerDestination == null || !brokerDestination.startsWith(prefix)) {
            return null;
        }
        String key = brokerDestination.substring(prefix.length());
        int separator = key.indexOf('.');
        if (separator < 1 || !key.substring(0, separator).matches("[1-9][0-9]*")) {
            return null;
        }
        String destination = "/user/queue/" + key.substring(separator + 1);
        return USER_SUBSCRIPTIONS.contains(destination) ? destination : null;
    }
}
