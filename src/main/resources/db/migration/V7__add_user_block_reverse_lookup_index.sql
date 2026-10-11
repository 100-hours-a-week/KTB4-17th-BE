CREATE INDEX `idx_user_blocks_blocked_blocker_active`
  ON `user_blocks` (`blocked_user_id`, `blocker_user_id`, `unblocked_at`);
