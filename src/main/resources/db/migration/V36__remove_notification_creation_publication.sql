UPDATE `notification_request_outbox`
SET `status` = 'PENDING',
    `failure_count` = 0,
    `last_failure_type` = NULL,
    `next_attempt_at` = UTC_TIMESTAMP(6),
    `processing_token` = NULL,
    `processing_until` = NULL,
    `completed_at` = NULL
WHERE `status` = 'PUBLISHED';

ALTER TABLE `notification_request_outbox`
  DROP CHECK `chk_notification_outbox_status`,
  ADD CONSTRAINT `chk_notification_outbox_status`
    CHECK (`status` IN ('PENDING', 'FAILED', 'COMPLETED')),
  DROP COLUMN `published_at`;
