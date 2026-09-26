package com.team.dating_backend.chat.enums;

public enum ChatOutboxFailureCode {
    MESSAGE_NOT_FOUND, PARTICIPANTS_INVALID, BROKER_PUBLISH_FAILED, DATABASE_ACCESS_FAILED, RETRY_LIMIT_EXCEEDED
}
