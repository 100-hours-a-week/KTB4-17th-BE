CREATE INDEX idx_recommendation_batch_user_deleted
    ON recommendation_batches (user_id, deleted_at);

CREATE INDEX idx_recommendation_item_batch_id
    ON recommendation_items (recommendation_batch_id, id);

INSERT INTO publisher_locks (lock_name, owner_id, locked_until, updated_at)
VALUES ('recommendation-refresh', NULL, '1970-01-01 00:00:00.000000', CURRENT_TIMESTAMP(6));
