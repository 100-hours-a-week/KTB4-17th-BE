package com.team.dating_backend.aipractice.service;

import com.team.dating_backend.aipractice.dto.event.AiPracticeChatUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.team.dating_backend.chat.service.RabbitUserEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiPracticeWebSocketEventPublisher {

    private static final String DESTINATION = "/queue/ai-practice";

    private final RabbitUserEventPublisher eventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(AiPracticeChatUpdatedEvent event) {
        try {
            // 다른 WS 서버에 연결된 사용자에게도 연습 결과를 전달한다.
            eventPublisher.publish(
                event.userId().toString(), DESTINATION, event);
        } catch (RuntimeException exception) {
            log.warn("AI Practice websocket event delivery failed. sessionId={}, chatId={}",
                event.sessionId(), event.chatId());
        }
    }
}
