CREATE TABLE `chat_delegation_messages` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `delegation_id` bigint NOT NULL,
  `turn` int NOT NULL,
  `request_id` binary(16) NOT NULL,
  `context_message_id` bigint DEFAULT NULL,
  `ai_message_id` bigint DEFAULT NULL,
  `content` varchar(1000) DEFAULT NULL,
  `status` varchar(20) NOT NULL,
  `generation_failure_count` int NOT NULL DEFAULT 0,
  `transport_retry_count` int NOT NULL DEFAULT 0,
  `next_attempt_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_delegation_message_request` (`request_id`),
  UNIQUE KEY `uk_chat_delegation_message_turn` (`delegation_id`, `turn`),
  KEY `idx_chat_delegation_message_context` (`context_message_id`),
  CONSTRAINT `fk_chat_delegation_message_delegation`
    FOREIGN KEY (`delegation_id`) REFERENCES `chat_delegations` (`id`),
  CONSTRAINT `fk_chat_delegation_message_context`
    FOREIGN KEY (`context_message_id`) REFERENCES `chat_messages` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
