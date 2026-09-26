CREATE TABLE chat_message_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    chat_message_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    failure_count INT NOT NULL,
    last_failure_type VARCHAR(100) NULL,
    published_at DATETIME(6) NULL,
    failed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_chat_message_outbox_message UNIQUE (chat_message_id),
    INDEX idx_chat_message_outbox_due (published_at, failed_at, next_attempt_at, id)
) ENGINE=InnoDB;
