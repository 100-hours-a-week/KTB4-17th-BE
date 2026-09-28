-- Apply once after backing up the database.
-- target_member_id remains nullable for sessions created before this feature update.
ALTER TABLE ai_practice_sessions
    ADD COLUMN target_member_id BIGINT NULL,
    ADD COLUMN ai_session_id VARCHAR(100) NULL,
    ADD COLUMN end_command_enqueued BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT uk_ai_practice_session_ai_session_id UNIQUE (ai_session_id),
    ADD INDEX idx_ai_practice_session_user_target_status (user_id, target_member_id, status),
    ADD CONSTRAINT fk_ai_practice_target_member
        FOREIGN KEY (target_member_id) REFERENCES users (id);

ALTER TABLE ai_practice_chats
    ADD COLUMN client_message_id BINARY(16) NULL,
    ADD COLUMN generation_attempt INT NOT NULL DEFAULT 1,
    ADD COLUMN usage_date DATE NULL,
    ADD COLUMN failure_code VARCHAR(100) NULL;

UPDATE ai_practice_chats
SET client_message_id = UNHEX(REPLACE(UUID(), '-', ''))
WHERE client_message_id IS NULL;

UPDATE ai_practice_chats
SET usage_date = DATE(created_at)
WHERE usage_date IS NULL;

ALTER TABLE ai_practice_chats
    MODIFY COLUMN client_message_id BINARY(16) NOT NULL,
    MODIFY COLUMN usage_date DATE NOT NULL,
    ADD CONSTRAINT uk_ai_practice_chat_client_message UNIQUE (session_id, client_message_id),
    ADD INDEX idx_ai_practice_chat_session_id (session_id, id);

CREATE TABLE ai_practice_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    command_type VARCHAR(30) NOT NULL,
    session_id BIGINT NOT NULL,
    chat_id BIGINT NULL,
    generation_attempt INT NOT NULL DEFAULT 0,
    idempotency_key BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    failure_count INT NOT NULL DEFAULT 0,
    last_failure_type VARCHAR(100) NULL,
    published_at DATETIME(6) NULL,
    failed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_ai_practice_outbox_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_ai_practice_outbox_session
        FOREIGN KEY (session_id) REFERENCES ai_practice_sessions (id),
    CONSTRAINT fk_ai_practice_outbox_chat
        FOREIGN KEY (chat_id) REFERENCES ai_practice_chats (id),
    INDEX idx_ai_practice_outbox_due (published_at, failed_at, next_attempt_at, id)
) ENGINE=InnoDB;
