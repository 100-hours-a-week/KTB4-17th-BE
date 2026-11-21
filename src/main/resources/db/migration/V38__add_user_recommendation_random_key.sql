ALTER TABLE users
    ADD COLUMN recommendation_random_key BIGINT NULL;

UPDATE users
SET recommendation_random_key = FLOOR(RAND() * 9223372036854775807);

ALTER TABLE users
    MODIFY COLUMN recommendation_random_key BIGINT NOT NULL;

CREATE INDEX idx_users_recommendation_random_key_id
    ON users (recommendation_random_key, id);
