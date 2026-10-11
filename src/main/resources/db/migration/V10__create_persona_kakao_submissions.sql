-- Request metadata only. Original exports, messages and speaker names are never stored here.
CREATE TABLE persona_kakao_submissions (
    id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(36) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    submission_status VARCHAR(16) NOT NULL,
    ai_job_id VARCHAR(128) NULL,
    job_status VARCHAR(16) NULL,
    persona_id VARCHAR(128) NULL,
    analyzed_count INT NOT NULL DEFAULT 0,
    error_code VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_kakao_submission_user_key (user_id, idempotency_key),
    CONSTRAINT fk_kakao_submission_user FOREIGN KEY (user_id) REFERENCES users (id),
    CHECK (submission_status IN ('SUBMITTING', 'SUBMITTED', 'REJECTED', 'UNKNOWN')),
    CHECK (job_status IS NULL OR job_status IN ('pending', 'running', 'succeeded', 'failed')),
    CHECK (analyzed_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
