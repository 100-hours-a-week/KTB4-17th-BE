CREATE INDEX `idx_user_blocks_blocker_blocked_active`
  ON `user_blocks` (`blocker_user_id`, `blocked_user_id`, `unblocked_at`);
