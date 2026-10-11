ALTER TABLE `matches`
  ADD COLUMN `ended_at` datetime(6) DEFAULT NULL,
  ADD COLUMN `end_reason` varchar(30) DEFAULT NULL,
  ADD INDEX `idx_matches_sender_receiver_status`
    (`sender_id`, `receiver_id`, `status`);
