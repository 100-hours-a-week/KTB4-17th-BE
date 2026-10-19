-- 컬럼만 추가해 Blue 저장과 기존 발행 이력을 유지한다.
-- 발행 순서 인덱스는 유지하고 선점 갱신·완료는 기본키로 처리한다.
ALTER TABLE chat_message_outbox
    ADD COLUMN rabbit_claim_token CHAR(36) DEFAULT NULL,
    ADD COLUMN rabbit_claim_until DATETIME(6) DEFAULT NULL;
