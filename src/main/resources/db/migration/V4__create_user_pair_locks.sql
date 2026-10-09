CREATE TABLE `user_pair_locks` (
  `lower_user_id` bigint NOT NULL,
  `higher_user_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`lower_user_id`, `higher_user_id`),
  CONSTRAINT `chk_user_pair_locks_order` CHECK (`lower_user_id` < `higher_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
