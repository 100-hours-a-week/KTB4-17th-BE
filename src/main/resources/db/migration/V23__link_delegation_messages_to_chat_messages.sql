ALTER TABLE `chat_delegation_messages`
  ADD COLUMN `chat_message_id` bigint DEFAULT NULL AFTER `content`,
  ADD UNIQUE KEY `uk_chat_delegation_message_chat_message` (`chat_message_id`),
  ADD CONSTRAINT `fk_chat_delegation_message_chat_message`
    FOREIGN KEY (`chat_message_id`) REFERENCES `chat_messages` (`id`);
