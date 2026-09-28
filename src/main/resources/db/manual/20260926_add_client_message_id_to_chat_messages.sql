-- Apply once after backing up the database.
-- Existing messages receive server-generated UUIDs so the new column can be NOT NULL.
ALTER TABLE chat_messages
    ADD COLUMN client_message_id BINARY(16) NULL;

UPDATE chat_messages
SET client_message_id = UNHEX(REPLACE(UUID(), '-', ''))
WHERE client_message_id IS NULL;

ALTER TABLE chat_messages
    MODIFY COLUMN client_message_id BINARY(16) NOT NULL,
    ADD CONSTRAINT uk_chat_message_sender_client_id
        UNIQUE (sender_id, client_message_id);
