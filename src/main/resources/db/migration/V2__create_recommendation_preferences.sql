CREATE TABLE `recommendation_preferences` (
  `user_id` bigint NOT NULL,
  `min_age` smallint DEFAULT NULL,
  `max_age` smallint DEFAULT NULL,
  `min_height` smallint DEFAULT NULL,
  `max_height` smallint DEFAULT NULL,
  `religion` json NOT NULL DEFAULT (JSON_ARRAY()),
  `drinking` json NOT NULL DEFAULT (JSON_ARRAY()),
  `smoking` json NOT NULL DEFAULT (JSON_ARRAY()),
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `deleted_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`user_id`),
  CONSTRAINT `fk_recommendation_preferences_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
