SET @notification_outbox_has_completed_at = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'notification_request_outbox'
      AND column_name = 'completed_at'
);

SET @notification_outbox_add_completed_at_sql = IF(
    @notification_outbox_has_completed_at = 0,
    'ALTER TABLE `notification_request_outbox` ADD COLUMN `completed_at` datetime(6) DEFAULT NULL',
    'DO 0'
);

PREPARE notification_outbox_add_completed_at FROM @notification_outbox_add_completed_at_sql;
EXECUTE notification_outbox_add_completed_at;
DEALLOCATE PREPARE notification_outbox_add_completed_at;

UPDATE `notification_request_outbox`
SET `status` = 'PENDING',
    `failure_count` = 0,
    `last_failure_type` = NULL,
    `next_attempt_at` = UTC_TIMESTAMP(6),
    `processing_token` = NULL,
    `processing_until` = NULL,
    `completed_at` = NULL
WHERE `status` = 'PUBLISHED';

SET @notification_outbox_has_status_check = (
    SELECT COUNT(*)
    FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE()
      AND table_name = 'notification_request_outbox'
      AND constraint_name = 'chk_notification_outbox_status'
      AND constraint_type = 'CHECK'
);

SET @notification_outbox_has_published_at = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'notification_request_outbox'
      AND column_name = 'published_at'
);

SET @notification_outbox_alter_sql = CONCAT(
    'ALTER TABLE `notification_request_outbox` ',
    IF(
        @notification_outbox_has_status_check > 0,
        'DROP CHECK `chk_notification_outbox_status`, ',
        ''
    ),
    'ADD CONSTRAINT `chk_notification_outbox_status` ',
    'CHECK (`status` IN (''PENDING'', ''FAILED'', ''COMPLETED''))',
    IF(@notification_outbox_has_published_at > 0, ', DROP COLUMN `published_at`', '')
);

PREPARE notification_outbox_cutover FROM @notification_outbox_alter_sql;
EXECUTE notification_outbox_cutover;
DEALLOCATE PREPARE notification_outbox_cutover;
