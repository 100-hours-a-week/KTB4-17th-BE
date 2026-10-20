ALTER TABLE `chat_delegations`
  ADD COLUMN `approved_at` datetime(6) DEFAULT NULL AFTER `status`,
  ADD COLUMN `next_action_at` datetime(6) DEFAULT NULL AFTER `approved_at`;
