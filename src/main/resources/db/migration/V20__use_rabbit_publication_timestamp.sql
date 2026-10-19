-- V19의 불리언 발행 상태를 보존한 뒤 해당 컬럼을 제거한다.
-- 완료 행은 시각이 없어도 완료로, 대기 행은 시각이 남아 있어도 대기로 유지한다.
UPDATE chat_message_outbox
SET rabbit_published_at = CASE
    WHEN rabbit_published = TRUE THEN COALESCE(rabbit_published_at, CURRENT_TIMESTAMP(6))
    ELSE NULL
END
WHERE (rabbit_published = TRUE AND rabbit_published_at IS NULL)
   OR (rabbit_published = FALSE AND rabbit_published_at IS NOT NULL);

-- Blue의 published_at 컬럼과 기존 인덱스는 그대로 유지한다.
ALTER TABLE chat_message_outbox
    DROP INDEX idx_chat_message_outbox_rabbit_due,
    DROP COLUMN rabbit_published,
    ADD INDEX idx_chat_message_outbox_rabbit_due
        (rabbit_published_at, rabbit_failed_at, rabbit_next_attempt_at, id);
