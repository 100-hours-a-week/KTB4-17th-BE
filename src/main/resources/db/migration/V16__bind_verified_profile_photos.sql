ALTER TABLE `face_verification_sessions`
  ADD COLUMN `verified_file_id` bigint DEFAULT NULL,
  ADD KEY `idx_face_verification_verified_file` (`verified_file_id`),
  ADD CONSTRAINT `fk_face_verification_verified_file`
    FOREIGN KEY (`verified_file_id`) REFERENCES `files` (`id`);

UPDATE `face_verification_sessions` session
JOIN `file_upload_intents` intent ON intent.id = session.upload_intent_id
JOIN `files` file ON file.id = intent.completed_file_id
SET session.verified_file_id = file.id
WHERE session.status = 'VERIFIED'
  AND intent.status = 'COMPLETED'
  AND intent.owner_user_id = session.owner_user_id
  AND file.owner_user_id = session.owner_user_id
  AND intent.primary_photo_check_version_token = session.photo_version_token;
