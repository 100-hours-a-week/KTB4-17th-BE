-- 2026-10-07 개발 DB를 기준으로 확정한 공통 스키마를 재현한다.
-- 데이터와 AUTO_INCREMENT 현재값은 포함하지 않는다.
-- 신규 DB는 V1을 실행한다. 기존 DB는 전체 구조 대조 후 별도 baseline(1)이 필요하다.
-- 이미 배포한 마이그레이션은 수정하지 않고 다음 버전에서 변경한다.

CREATE TABLE `activity_regions` (
  `representative_latitude` decimal(9,6) NOT NULL,
  `representative_longitude` decimal(9,6) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `region_code` varchar(30) NOT NULL,
  `province_name` varchar(50) NOT NULL,
  `region_name` varchar(50) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK1lhcyu8kimgtp2qpp2p3pivm2` (`region_code`),
  CONSTRAINT `activity_regions_chk_1` CHECK (((`representative_latitude` between -(90) and 90) and (`representative_longitude` between -(180) and 180)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `ai_practice_outbox` (
  `failure_count` int NOT NULL,
  `generation_attempt` int NOT NULL,
  `chat_id` bigint DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `failed_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `next_attempt_at` datetime(6) NOT NULL,
  `published_at` datetime(6) DEFAULT NULL,
  `session_id` bigint NOT NULL,
  `idempotency_key` binary(16) NOT NULL,
  `last_failure_type` varchar(100) DEFAULT NULL,
  `command_type` enum('END_SESSION','GENERATE') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_practice_outbox_idempotency_key` (`idempotency_key`),
  KEY `idx_ai_practice_outbox_due` (`published_at`,`failed_at`,`next_attempt_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `ai_practice_sessions` (
  `end_command_enqueued` bit(1) NOT NULL,
  `ended_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `started_at` datetime(6) NOT NULL,
  `target_member_id` bigint DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `ai_session_id` varchar(100) DEFAULT NULL,
  `status` enum('ACTIVE','ENDED') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_practice_session_ai_session_id` (`ai_session_id`),
  KEY `idx_ai_practice_session_user_target_status` (`user_id`,`target_member_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `ai_simulation_sessions` (
  `created_at` datetime(6) NOT NULL,
  `finished_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `status` enum('CANCELED','COMPLETED','FAILED','IN_PROGRESS') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ai_simulation_session_user_status_id` (`user_id`,`status`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `chat_message_outbox` (
  `failure_count` int NOT NULL,
  `chat_message_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `failed_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `next_attempt_at` datetime(6) NOT NULL,
  `published_at` datetime(6) DEFAULT NULL,
  `last_failure_type` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_message_outbox_message` (`chat_message_id`),
  KEY `idx_chat_message_outbox_due` (`published_at`,`failed_at`,`next_attempt_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `file_upload_intents` (
  `completed_file_id` bigint DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `owner_user_id` bigint NOT NULL,
  `staging_cleaned_at` datetime(6) DEFAULT NULL,
  `declared_content_type` varchar(100) NOT NULL,
  `final_storage_key` varchar(512) NOT NULL,
  `staging_key` varchar(512) NOT NULL,
  `original_name` varchar(255) NOT NULL,
  `status` enum('COMPLETED','EXPIRED','FAILED','PENDING','PROCESSING') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_upload_intents_staging_key` (`staging_key`),
  UNIQUE KEY `uk_file_upload_intents_final_key` (`final_storage_key`),
  KEY `idx_file_upload_intents_cleanup` (`expires_at`,`staging_cleaned_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `files` (
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `file_size` bigint NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `owner_user_id` bigint NOT NULL,
  `mime_type` varchar(100) NOT NULL,
  `storage_key` varchar(512) NOT NULL,
  `original_name` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_files_storage_key` (`storage_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `likes` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `receiver_id` bigint NOT NULL,
  `resolved_at` datetime(6) DEFAULT NULL,
  `sender_id` bigint NOT NULL,
  `status` enum('BLOCKED','MATCHED','PENDING','REJECTED','WITHDRAWN') NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `matches` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `matched_at` datetime(6) NOT NULL,
  `receiver_id` bigint NOT NULL,
  `sender_id` bigint NOT NULL,
  `status` enum('ACTIVE','ENDED') NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `recommendation_batches` (
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `generation_type` enum('DEFAULT','PREFERENCE') NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `recommendation_items` (
  `ranking_order` int NOT NULL,
  `candidate_user_id` bigint NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `recommendation_batch_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recommendation_item_batch_ranking` (`recommendation_batch_id`,`ranking_order`),
  UNIQUE KEY `uk_recommendation_item_batch_candidate` (`recommendation_batch_id`,`candidate_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `recommendation_passes` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `passed_user_id` bigint NOT NULL,
  `passer_user_id` bigint NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user_blocks` (
  `blocked_at` datetime(6) NOT NULL,
  `blocked_user_id` bigint NOT NULL,
  `blocker_user_id` bigint NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `unblocked_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `users` (
  `birth_date` date NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `last_accessed_at` datetime(6) NOT NULL,
  `name` varchar(8) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `withdrawn_at` datetime(6) DEFAULT NULL,
  `face_verification_status` enum('NOT_VERIFIED','PENDING','REJECTED','VERIFIED') NOT NULL,
  `gender` enum('FEMALE','MALE') NOT NULL,
  `persona_onboarding_status` enum('BYPASSED','CONFIRMED','IN_PROGRESS','PENDING') NOT NULL,
  `status` enum('ACTIVE','ONBOARDING','SUSPENDED','WITHDRAWN') NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `profiles` (
  `height` smallint DEFAULT NULL,
  `activity_region_id` bigint DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL,
  `nickname` varchar(10) DEFAULT NULL,
  `job` varchar(50) DEFAULT NULL,
  `body_type` enum('AVERAGE','CHUBBY','LARGE_BUILD','LEAN_MUSCULAR','MUSCULAR','THIN') DEFAULT NULL,
  `drinking` enum('FREQUENT','NEVER','OCCASIONAL','SOCIAL') DEFAULT NULL,
  `education_level` enum('ASSOCIATE','BACHELOR','DOCTORATE','HIGH_SCHOOL','MASTER','OTHER') DEFAULT NULL,
  `mbti` enum('ENFJ','ENFP','ENTJ','ENTP','ESFJ','ESFP','ESTJ','ESTP','INFJ','INFP','INTJ','INTP','ISFJ','ISFP','ISTJ','ISTP') DEFAULT NULL,
  `religion` enum('BUDDHIST','CATHOLIC','NONE','OTHER','PROTESTANT') DEFAULT NULL,
  `smoking` enum('FREQUENT','NON_SMOKER','OCCASIONAL') DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_profiles_user_id` (`user_id`),
  UNIQUE KEY `uk_profiles_active_nickname` (((case when (`deleted_at` is null) then `nickname` else NULL end))),
  KEY `FKk3hurjp1hgrao5tb13uj5o5b5` (`activity_region_id`),
  CONSTRAINT `FK410q61iev7klncmpqfuo85ivh` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `FKk3hurjp1hgrao5tb13uj5o5b5` FOREIGN KEY (`activity_region_id`) REFERENCES `activity_regions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user_auth_accounts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `linked_at` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL,
  `provider_user_id` varchar(255) NOT NULL,
  `provider` enum('KAKAO') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_auth_accounts_provider_provider_user_id` (`provider`,`provider_user_id`),
  KEY `FK2kv5yjdnpqdirwd8b2kqv79ti` (`user_id`),
  CONSTRAINT `FK2kv5yjdnpqdirwd8b2kqv79ti` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `ai_practice_chats` (
  `generation_attempt` int NOT NULL,
  `is_retry` bit(1) NOT NULL,
  `usage_date` date NOT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` bigint NOT NULL,
  `client_message_id` binary(16) NOT NULL,
  `failure_code` varchar(100) DEFAULT NULL,
  `user_message` varchar(500) NOT NULL,
  `ai_response` text,
  `status` enum('COMPLETED','FAILED','GENERATING') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_practice_chat_client_message` (`session_id`,`client_message_id`),
  KEY `idx_ai_practice_chat_session_id` (`session_id`,`id`),
  CONSTRAINT `FKb1ti8q1b9o7qn13v5fe16tlbo` FOREIGN KEY (`session_id`) REFERENCES `ai_practice_sessions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `ai_simulation_messages` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` bigint NOT NULL,
  `content` json NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ai_simulation_message_session_id` (`session_id`,`id`),
  CONSTRAINT `FKpw932ldw3l3nplgk4on1frix7` FOREIGN KEY (`session_id`) REFERENCES `ai_simulation_sessions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `ai_simulation_reports` (
  `conversation_flow_grade` varchar(1) DEFAULT NULL,
  `initiative_balance_grade` varchar(1) DEFAULT NULL,
  `interest_expression_grade` varchar(1) DEFAULT NULL,
  `overall_score` decimal(5,2) DEFAULT NULL,
  `question_exchange_grade` varchar(1) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` bigint NOT NULL,
  `summary` text,
  `detail_result` json DEFAULT NULL,
  `status` enum('COMPLETED','FAILED','GENERATING') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_simulation_report_session_id` (`session_id`),
  CONSTRAINT `FKnqv0rckpkgcsnov5cd5cs2acj` FOREIGN KEY (`session_id`) REFERENCES `ai_simulation_sessions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `chat_rooms` (
  `created_at` datetime(6) NOT NULL,
  `ended_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `match_id` bigint NOT NULL,
  `end_reason` enum('AI_DELEGATION_COMPLETED','BLOCKED','USER_LEFT_CHAT','USER_WITHDRAWN') DEFAULT NULL,
  `status` enum('ACTIVE','ENDED','INACTIVE') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKg4i36pmghdo03qrb7oauw90j4` (`match_id`),
  CONSTRAINT `fk_chat_room_match` FOREIGN KEY (`match_id`) REFERENCES `matches` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `profile_images` (
  `display_order` smallint NOT NULL,
  `is_frontal` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `image_id` bigint NOT NULL,
  `profile_id` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_profile_images_profile_active_order` (`profile_id`,`deleted_at`,`display_order`),
  KEY `FKdclrwh8icjatnfeoqaxs14ojr` (`image_id`),
  CONSTRAINT `FK5mhebqjvg6q01bug8nawluykf` FOREIGN KEY (`profile_id`) REFERENCES `profiles` (`id`),
  CONSTRAINT `FKdclrwh8icjatnfeoqaxs14ojr` FOREIGN KEY (`image_id`) REFERENCES `files` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `chat_participants` (
  `is_chat_notification` bit(1) NOT NULL,
  `chat_room_id` bigint NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `last_read_message_id` bigint DEFAULT NULL,
  `left_at` datetime(6) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `status` enum('ACTIVE','LEFT') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_participant_room_user` (`chat_room_id`,`user_id`),
  UNIQUE KEY `uk_chat_participant_id_room` (`id`,`chat_room_id`),
  KEY `idx_chat_participant_user_status_room` (`user_id`,`status`,`chat_room_id`),
  CONSTRAINT `fk_chat_participant_room` FOREIGN KEY (`chat_room_id`) REFERENCES `chat_rooms` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `chat_messages` (
  `chat_room_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `file_id` bigint DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `receiver_deleted_at` datetime(6) DEFAULT NULL,
  `sender_deleted_at` datetime(6) DEFAULT NULL,
  `sender_id` bigint NOT NULL,
  `client_message_id` binary(16) NOT NULL,
  `text_content` varchar(1000) DEFAULT NULL,
  `message_type` enum('IMAGE','TEXT') NOT NULL,
  `status` enum('FAILED','SENT') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_message_sender_client_id` (`sender_id`,`client_message_id`),
  KEY `idx_chat_message_room_status_id` (`chat_room_id`,`status`,`id`),
  KEY `idx_chat_message_room_sender_status_id` (`chat_room_id`,`sender_id`,`status`,`id`),
  KEY `fk_chat_message_file` (`file_id`),
  CONSTRAINT `fk_chat_message_file` FOREIGN KEY (`file_id`) REFERENCES `files` (`id`),
  CONSTRAINT `fk_chat_message_room` FOREIGN KEY (`chat_room_id`) REFERENCES `chat_rooms` (`id`),
  CONSTRAINT `fk_chat_message_sender` FOREIGN KEY (`sender_id`) REFERENCES `chat_participants` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
