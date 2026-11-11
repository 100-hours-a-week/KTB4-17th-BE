ALTER TABLE `notification_request_outbox`
  DROP CHECK `chk_notification_outbox_status`,
  ADD CONSTRAINT `chk_notification_outbox_status`
    CHECK (`status` IN ('PENDING', 'FAILED', 'COMPLETED')),
  DROP COLUMN `published_at`;
