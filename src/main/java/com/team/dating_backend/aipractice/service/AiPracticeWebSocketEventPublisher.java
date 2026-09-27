package com.team.dating_backend.aipractice.service;

import com.team.dating_backend.aipractice.dto.event.AiPracticeTurnUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiPracticeWebSocketEventPublisher {

    private static final String DESTINATION = "/queue/ai-practice";

    private final SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(AiPracticeTurnUpdatedEvent event) {
        try {
            messagingTemplate.convertAndSendToUser(
                event.userId().toString(), DESTINATION, event);
        } catch (RuntimeException exception) {
            log.warn("AI Practice websocket event delivery failed. sessionId={}, chatId={}",
                event.sessionId(), event.chatId());
        }
    }
}
