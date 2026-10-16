CREATE TABLE `face_verification_sessions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `owner_user_id` bigint NOT NULL,
  `upload_intent_id` bigint NOT NULL,
  `photo_version_token` varchar(255) NOT NULL,
  `challenge_token` varchar(2048) NOT NULL,
  `status` enum('READY','PROCESSING','VERIFIED','NOT_VERIFIED','EXHAUSTED','EXPIRED') NOT NULL,
  `attempts_used` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_face_verification_owner_id` (`owner_user_id`,`id`),
  CONSTRAINT `fk_face_verification_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `chk_face_verification_attempts_used` CHECK (`attempts_used` BETWEEN 0 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `face_verification_attempts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` bigint NOT NULL,
  `attempt_no` int NOT NULL,
  `video_hash` varchar(64) NOT NULL,
  `content_type` varchar(50) NOT NULL,
  `status` enum('PROCESSING','COMPLETED','ERROR') NOT NULL,
  `decision` enum('VERIFIED','RETRY','NOT_VERIFIED') DEFAULT NULL,
  `reason_codes` json NOT NULL,
  `error_code` varchar(100) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `finished_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_face_verification_attempt_no` (`session_id`,`attempt_no`),
  UNIQUE KEY `uk_face_verification_video_hash` (`session_id`,`video_hash`),
  CONSTRAINT `fk_face_verification_attempt_session` FOREIGN KEY (`session_id`) REFERENCES `face_verification_sessions` (`id`),
  CONSTRAINT `chk_face_verification_attempt_no` CHECK (`attempt_no` BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
