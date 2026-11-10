ALTER TABLE `notification_request_outbox`
  ADD COLUMN `completed_at` datetime(6) DEFAULT NULL,
  DROP CHECK `chk_notification_outbox_status`,
  ADD CONSTRAINT `chk_notification_outbox_status`
    CHECK (`status` IN ('PENDING', 'PUBLISHED', 'FAILED', 'COMPLETED'));
