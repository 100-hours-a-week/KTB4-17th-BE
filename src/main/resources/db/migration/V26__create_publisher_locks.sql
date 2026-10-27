CREATE TABLE `publisher_locks` (
  `lock_name` varchar(100) NOT NULL,
  `owner_id` varchar(100) DEFAULT NULL,
  `locked_until` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`lock_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO `publisher_locks` (`lock_name`, `owner_id`, `locked_until`, `updated_at`)
VALUES (
  'chat-message-outbox-publisher',
  NULL,
  '1970-01-01 00:00:00.000000',
  CURRENT_TIMESTAMP(6)
);
