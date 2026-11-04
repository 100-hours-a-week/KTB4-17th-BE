CREATE INDEX `idx_notifications_user_deleted_created_id`
  ON `notifications` (`user_id`, `deleted_at`, `created_at`, `id`);
