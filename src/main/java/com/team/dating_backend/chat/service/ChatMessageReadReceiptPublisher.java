package com.team.dating_backend.chat.service;

import com.team.dating_backend.chat.dto.event.ChatMessageReadRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessageReadReceiptPublisher {

    private static final String READ_RECEIPT_DESTINATION = "/queue/chat-read-receipts";

    private final SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(ChatMessageReadRequestedEvent event) {
        try {
            messagingTemplate.convertAndSendToUser(
                event.recipientUserId().toString(), READ_RECEIPT_DESTINATION, event.receipt());
        } catch (RuntimeException exception) {
            log.warn("채팅 읽음 이벤트 전송에 실패했습니다. chatRoomId={}, lastReadMessageId={}",
                event.receipt().chatRoomId(), event.receipt().lastReadMessageId(), exception);
        }
    }
}
