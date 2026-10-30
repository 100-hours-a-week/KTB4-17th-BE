package com.team.dating_backend.chat.enums;

public enum ChatRealtimeRelayFailureCode {
    EVENT_SERIALIZATION_FAILED,
    EVENT_DESERIALIZATION_FAILED,
    REDIS_PUBLISH_FAILED,
    WEBSOCKET_PUBLISH_FAILED
}
