package com.team.dating_backend.chat.exception;

import com.team.dating_backend.chat.enums.ChatOutboxFailureCode;
import lombok.Getter;

@Getter
public class ChatMessageKafkaEventCreationException extends RuntimeException {

    private final ChatOutboxFailureCode failureCode;

    public ChatMessageKafkaEventCreationException(ChatOutboxFailureCode failureCode) {
        super(failureCode.name());
        this.failureCode = failureCode;
    }
}
