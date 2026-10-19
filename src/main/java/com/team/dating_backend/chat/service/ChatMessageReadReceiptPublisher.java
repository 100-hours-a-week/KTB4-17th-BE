package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.dto.event.ChatMessageReadRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessageReadReceiptPublisher {

    private static final String READ_RECEIPT_DESTINATION = "/queue/chat-read-receipts";

    private final RabbitUserEventPublisher eventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(ChatMessageReadRequestedEvent event) {
        try {
            // 읽음 처리 서버와 상대방의 WS 접속 서버가 달라도 이벤트를 전달한다.
            eventPublisher.publish(
                event.recipientUserId().toString(), READ_RECEIPT_DESTINATION, event.receipt());
        } catch (RuntimeException exception) {
            log.warn("채팅 읽음 이벤트 전송에 실패했습니다. chatRoomId={}, lastReadMessageId={}, exceptionType={}",
                event.receipt().chatRoomId(), event.receipt().lastReadMessageId(),
                exception.getClass().getSimpleName());
        }
    }
}
