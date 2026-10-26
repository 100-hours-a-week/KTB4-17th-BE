package com.team.dating_backend.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.team.dating_backend.notification.support.NotificationMultiProcessTestSupport;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("notification-multiprocess")
@Timeout(90)
class NotificationMultiProcessIntegrationTest extends NotificationMultiProcessTestSupport {

    @Test
    void 서버_A의_좋아요를_양쪽_서버의_수신자_연결에서_받고_다른_회원에게_전달하지_않는다() throws Exception {
        long sender = activeUser();
        long recipient = activeUser();
        long unrelated = activeUser();
        var onFirst = subscribe(first, recipient);
        var onSecond = subscribe(second, recipient);
        var anotherOnSecond = subscribe(second, recipient);
        var otherUser = subscribe(second, unrelated);

        var expected = likeNotification(first, sender, recipient);

        var notification = stored(expected);
        assertThat(notification.title()).isEqualTo("새로운 좋아요");
        assertThat(notification.body()).isEqualTo("u" + sender + "님이 당신에게 마음을 전했어요.");
        assertThat(notification.targetType()).isEqualTo("PROFILE");
        assertThat(notification.targetId()).isEqualTo(sender);
        reconcile("cross-server-like", List.of(expected), onFirst, onSecond, anotherOnSecond, otherUser);
    }

    @Test
    void 상호_좋아요의_두_매칭_알림과_채팅_알림이_동일한_저장_ID로_전파된다() throws Exception {
        long sender = activeUser();
        long recipient = activeUser();
        long unrelated = activeUser();
        var senderOnFirst = subscribe(first, sender);
        var senderOnSecond = subscribe(second, sender);
        var receiverOnSecond = subscribe(second, recipient);
        var otherUser = subscribe(second, unrelated);
        var originalLike = likeNotification(first, sender, recipient);

        assertThat(like(second, recipient, sender).path("status").asText()).isEqualTo("MATCHED");
        long matchId = jdbc.queryForObject("SELECT id FROM matches WHERE sender_id = ? AND receiver_id = ?",
            Long.class, sender, recipient);
        long roomId = jdbc.queryForObject("SELECT id FROM chat_rooms WHERE match_id = ?", Long.class, matchId);
        var senderMatch = new Expected("MATCH_CREATED:" + matchId, sender, "MATCH");
        var recipientMatch = new Expected("MATCH_CREATED:" + matchId, recipient, "MATCH");
        var chat = success(request(first, "POST", "/api/v1/chat-rooms/" + roomId + "/messages", token(sender),
            Map.of("clientMessageId", UUID.randomUUID().toString(), "messageType", "TEXT", "textContent",
                "서버를 건너온 메시지")),
            201);
        long messageId = chat.path("messageId").asLong();
        var message = new Expected("CHAT_MESSAGE_SENT:" + messageId, recipient, "CHAT_MESSAGE");

        assertThat(stored(senderMatch).createdAt()).isEqualTo(stored(recipientMatch).createdAt());
        assertThat(stored(senderMatch).targetId()).isEqualTo(roomId);
        assertThat(stored(message).targetId()).isEqualTo(roomId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message_outbox WHERE chat_message_id = ?",
            Long.class, messageId)).isEqualTo(1);
        reconcile("match-and-chat", List.of(originalLike, senderMatch, recipientMatch, message),
            senderOnFirst, senderOnSecond, receiverOnSecond, otherUser);
        var chatPage = success(request(second, "GET", NOTIFICATIONS + "?category=CHAT", token(recipient), null), 200);
        assertThat(chatPage.path("items")).hasSize(1);
        assertThat(chatPage.path("items").get(0).path("notificationId").asLong())
            .isEqualTo(stored(message).notificationId());
    }

    @Test
    void 실제_AI_Worker의_완료_결과가_Kafka와_다른_서버_SSE를_거쳐_요청자에게_전달된다() throws Exception {
        long requester = activeUser();
        long partner = activeUser();
        var onFirst = subscribe(first, requester);
        var onSecond = subscribe(second, requester);
        var partnerStream = subscribe(second, partner);
        int before = environment.aiRequests();

        var handle = success(request(first, "POST", "/api/v1/ai-simulations", token(requester),
            Map.of("targetUserId", partner)), 202);
        long simulationId = handle.path("simulationId").asLong();
        var expected = new Expected("AI_SIMULATION_COMPLETED:" + simulationId, requester,
            "AI_SIMULATION_REPORT_COMPLETED");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var state = success(request(second, "GET", "/api/v1/ai-simulations/" + simulationId + "/status",
                token(requester), null), 200);
            assertThat(state.path("status").asText()).isEqualTo("COMPLETED");
        });
        assertThat(environment.aiRequests()).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_simulation_messages WHERE session_id = ?", Long.class,
            simulationId)).isEqualTo(20);
        var report = success(
            request(second, "GET", "/api/v1/ai-simulations/" + simulationId + "/report", token(partner), null),
            200);
        assertThat(report.path("overall").path("score").asInt()).isEqualTo(78);
        assertThat(stored(expected).targetId()).isEqualTo(simulationId);
        reconcile("ai-completion", List.of(expected), onFirst, onSecond, partnerStream);
        var page = success(request(second, "GET", NOTIFICATIONS + "?category=SYSTEM", token(requester), null), 200);
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("items").get(0).path("notificationId").asLong())
            .isEqualTo(stored(expected).notificationId());
    }
}
