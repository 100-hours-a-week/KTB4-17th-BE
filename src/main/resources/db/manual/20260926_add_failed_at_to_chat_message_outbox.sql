-- Apply once only when chat_message_outbox was created with the previous schema.
-- Fresh databases should use 20260926_create_chat_message_outbox.sql instead.
ALTER TABLE chat_message_outbox
    ADD COLUMN failed_at DATETIME(6) NULL;

ALTER TABLE chat_message_outbox
    DROP INDEX idx_chat_message_outbox_due,
    ADD INDEX idx_chat_message_outbox_due (
        published_at,
        failed_at,
        next_attempt_at,
        id
    );

-- After fixing the recorded cause, an operator can requeue one failed event manually.
-- UPDATE chat_message_outbox
-- SET failed_at = NULL,
--     failure_count = 0,
--     next_attempt_at = CURRENT_TIMESTAMP(6)
-- WHERE id = <outbox_id>
--   AND published_at IS NULL
--   AND failed_at IS NOT NULL;
