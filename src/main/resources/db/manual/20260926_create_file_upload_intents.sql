CREATE TABLE file_upload_intents (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    staging_key VARCHAR(512) NOT NULL,
    final_storage_key VARCHAR(512) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    declared_content_type VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    completed_file_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    staging_cleaned_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_file_upload_intents_staging_key UNIQUE (staging_key),
    CONSTRAINT uk_file_upload_intents_final_key UNIQUE (final_storage_key),
    INDEX idx_file_upload_intents_cleanup (expires_at, staging_cleaned_at)
) ENGINE=InnoDB;
