ALTER TABLE `notifications`
  ADD COLUMN `source_event_id` varchar(255)
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL,
  ADD CONSTRAINT `uk_notifications_source_recipient_type`
    UNIQUE (`source_event_id`, `user_id`, `type`);
