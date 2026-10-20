ALTER TABLE `face_verification_sessions`
  ADD COLUMN `challenge_plan` json DEFAULT NULL,
  ADD COLUMN `protocol_version` tinyint NOT NULL DEFAULT 1;
