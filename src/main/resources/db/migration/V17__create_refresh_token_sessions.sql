CREATE TABLE `refresh_token_sessions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_key` binary(16) NOT NULL,
  `user_id` bigint NOT NULL,
  `current_token_hash` binary(32) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `last_rotated_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `revoked_at` datetime(6) DEFAULT NULL,
  `revocation_reason` varchar(32) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refresh_token_sessions_session_key` (`session_key`),
  UNIQUE KEY `uk_refresh_token_sessions_current_token_hash` (`current_token_hash`),
  KEY `idx_refresh_token_sessions_user_id` (`user_id`),
  KEY `idx_refresh_token_sessions_expires_at` (`expires_at`),
  CONSTRAINT `fk_refresh_token_sessions_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
