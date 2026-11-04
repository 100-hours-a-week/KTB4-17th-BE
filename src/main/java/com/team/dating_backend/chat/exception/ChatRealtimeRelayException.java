package com.team.dating_backend.chat.exception;

import com.team.dating_backend.chat.enums.ChatRealtimeRelayFailureCode;
import lombok.Getter;

@Getter
public class ChatRealtimeRelayException extends RuntimeException {

    private final ChatRealtimeRelayFailureCode failureCode;

    public ChatRealtimeRelayException(
        ChatRealtimeRelayFailureCode failureCode, Throwable cause) {
        super(failureCode.name(), cause);
        this.failureCode = failureCode;
    }
}
