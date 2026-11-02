ALTER TABLE `ai_practice_outbox`
  ADD COLUMN `processing_token` varchar(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  ADD COLUMN `processing_until` datetime(6) DEFAULT NULL,
  ADD CONSTRAINT `chk_ai_practice_outbox_processing`
  CHECK (
    (`processing_token` IS NULL AND `processing_until` IS NULL)
    OR (
      `processing_token` IS NOT NULL
      AND `processing_until` IS NOT NULL
      AND CHAR_LENGTH(`processing_token`) = 36
      AND CHAR_LENGTH(TRIM(`processing_token`)) > 0
    )
  );
