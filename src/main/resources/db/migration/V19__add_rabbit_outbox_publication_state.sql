-- 기존 컬럼·인덱스를 유지하고 컬럼만 추가해 Blue와 호환한다.
ALTER TABLE chat_message_outbox
    ADD COLUMN rabbit_published BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN rabbit_published_at DATETIME(6) DEFAULT NULL,
    ADD COLUMN rabbit_next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN rabbit_failure_count INT NOT NULL DEFAULT 0,
    ADD COLUMN rabbit_last_failure_type VARCHAR(100) DEFAULT NULL,
    ADD COLUMN rabbit_failed_at DATETIME(6) DEFAULT NULL,
    ADD INDEX idx_chat_message_outbox_rabbit_due
        (rabbit_published, rabbit_failed_at, rabbit_next_attempt_at, id);

-- Green 연결을 받기 전에 실행하며 과거 메시지 전체를 재발행하지 않는다.
-- 이후 Blue가 저장한 행은 기본값을 받아 Green의 발행 대상이 된다.
UPDATE chat_message_outbox
SET rabbit_published = TRUE, rabbit_published_at = published_at
WHERE published_at IS NOT NULL;
