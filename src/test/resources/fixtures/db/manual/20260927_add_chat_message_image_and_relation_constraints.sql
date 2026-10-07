-- Test fixture for applying chat constraints to a Hibernate-created schema.
SELECT participant.id, participant.chat_room_id
FROM chat_participants AS participant
LEFT JOIN chat_rooms AS room ON room.id = participant.chat_room_id
WHERE room.id IS NULL;

SELECT message.id, message.chat_room_id, message.sender_id
FROM chat_messages AS message
LEFT JOIN chat_rooms AS room ON room.id = message.chat_room_id
LEFT JOIN chat_participants AS sender ON sender.id = message.sender_id
WHERE room.id IS NULL
   OR sender.id IS NULL
   OR sender.chat_room_id <> message.chat_room_id;

-- Add the nullable image reference so existing text messages remain valid.
SET @has_chat_message_file_id = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'chat_messages'
      AND column_name = 'file_id'
);

SET @add_chat_message_file_id_sql = IF(
    @has_chat_message_file_id = 0,
    'ALTER TABLE chat_messages ADD COLUMN file_id BIGINT NULL',
    'SELECT 1'
);
PREPARE add_chat_message_file_id FROM @add_chat_message_file_id_sql;
EXECUTE add_chat_message_file_id;
DEALLOCATE PREPARE add_chat_message_file_id;

SELECT message.id, message.file_id
FROM chat_messages AS message
LEFT JOIN files AS file_record ON file_record.id = message.file_id
WHERE message.file_id IS NOT NULL
  AND file_record.id IS NULL;

SET @has_chat_message_room_sender_index = (
    SELECT COUNT(*)
    FROM (
        SELECT index_name
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'chat_messages'
          AND non_unique = 1
        GROUP BY index_name
        HAVING COUNT(*) = 4
           AND SUM(seq_in_index = 1 AND column_name = 'chat_room_id') = 1
           AND SUM(seq_in_index = 2 AND column_name = 'sender_id') = 1
           AND SUM(seq_in_index = 3 AND column_name = 'status') = 1
           AND SUM(seq_in_index = 4 AND column_name = 'id') = 1
    ) AS chat_message_indexes
);
SET @add_chat_message_room_sender_index_sql = IF(
    @has_chat_message_room_sender_index = 0,
    'ALTER TABLE chat_messages ADD INDEX idx_chat_message_room_sender_status_id (chat_room_id, sender_id, status, id)',
    'SELECT 1'
);
PREPARE add_chat_message_room_sender_index FROM @add_chat_message_room_sender_index_sql;
EXECUTE add_chat_message_room_sender_index;
DEALLOCATE PREPARE add_chat_message_room_sender_index;

-- A unique composite key lets the database enforce that a message sender is
-- a participant in that same room, rather than merely an existing participant.
SET @has_chat_participant_id_room_unique = (
    SELECT COUNT(*)
    FROM (
        SELECT index_name
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'chat_participants'
          AND non_unique = 0
        GROUP BY index_name
        HAVING COUNT(*) = 2
           AND SUM(seq_in_index = 1 AND column_name = 'id') = 1
           AND SUM(seq_in_index = 2 AND column_name = 'chat_room_id') = 1
    ) AS participant_unique_keys
);

SET @add_chat_participant_id_room_unique_sql = IF(
    @has_chat_participant_id_room_unique = 0,
    'ALTER TABLE chat_participants ADD CONSTRAINT uk_chat_participant_id_room UNIQUE (id, chat_room_id)',
    'SELECT 1'
);
PREPARE add_chat_participant_id_room_unique FROM @add_chat_participant_id_room_unique_sql;
EXECUTE add_chat_participant_id_room_unique;
DEALLOCATE PREPARE add_chat_participant_id_room_unique;

-- Add missing foreign keys by column relationship, regardless of the name
-- already present in the deployed schema.
SET @has_chat_participant_room_fk = (
    SELECT COUNT(*)
    FROM information_schema.key_column_usage
    WHERE table_schema = DATABASE()
      AND table_name = 'chat_participants'
      AND column_name = 'chat_room_id'
      AND referenced_table_schema = DATABASE()
      AND referenced_table_name = 'chat_rooms'
      AND referenced_column_name = 'id'
);
SET @add_chat_participant_room_fk_sql = IF(
    @has_chat_participant_room_fk = 0,
    'ALTER TABLE chat_participants ADD CONSTRAINT fk_chat_participant_room FOREIGN KEY (chat_room_id) REFERENCES chat_rooms (id)',
    'SELECT 1'
);
PREPARE add_chat_participant_room_fk FROM @add_chat_participant_room_fk_sql;
EXECUTE add_chat_participant_room_fk;
DEALLOCATE PREPARE add_chat_participant_room_fk;

SET @has_chat_message_room_fk = (
    SELECT COUNT(*)
    FROM information_schema.key_column_usage
    WHERE table_schema = DATABASE()
      AND table_name = 'chat_messages'
      AND column_name = 'chat_room_id'
      AND referenced_table_schema = DATABASE()
      AND referenced_table_name = 'chat_rooms'
      AND referenced_column_name = 'id'
);
SET @add_chat_message_room_fk_sql = IF(
    @has_chat_message_room_fk = 0,
    'ALTER TABLE chat_messages ADD CONSTRAINT fk_chat_message_room FOREIGN KEY (chat_room_id) REFERENCES chat_rooms (id)',
    'SELECT 1'
);
PREPARE add_chat_message_room_fk FROM @add_chat_message_room_fk_sql;
EXECUTE add_chat_message_room_fk;
DEALLOCATE PREPARE add_chat_message_room_fk;

SET @has_chat_message_sender_fk = (
    SELECT COUNT(*)
    FROM information_schema.key_column_usage
    WHERE table_schema = DATABASE()
      AND table_name = 'chat_messages'
      AND column_name = 'sender_id'
      AND referenced_table_schema = DATABASE()
      AND referenced_table_name = 'chat_participants'
      AND referenced_column_name = 'id'
);
SET @add_chat_message_sender_fk_sql = IF(
    @has_chat_message_sender_fk = 0,
    'ALTER TABLE chat_messages ADD CONSTRAINT fk_chat_message_sender FOREIGN KEY (sender_id) REFERENCES chat_participants (id)',
    'SELECT 1'
);
PREPARE add_chat_message_sender_fk FROM @add_chat_message_sender_fk_sql;
EXECUTE add_chat_message_sender_fk;
DEALLOCATE PREPARE add_chat_message_sender_fk;

SET @has_chat_message_sender_room_fk = (
    SELECT COUNT(*)
    FROM (
        SELECT constraint_name
        FROM information_schema.key_column_usage
        WHERE table_schema = DATABASE()
          AND table_name = 'chat_messages'
          AND referenced_table_schema = DATABASE()
          AND referenced_table_name = 'chat_participants'
        GROUP BY constraint_name
        HAVING COUNT(*) = 2
           AND SUM(ordinal_position = 1
               AND column_name = 'sender_id'
               AND referenced_column_name = 'id') = 1
           AND SUM(ordinal_position = 2
               AND column_name = 'chat_room_id'
               AND referenced_column_name = 'chat_room_id') = 1
    ) AS sender_room_foreign_keys
);
SET @add_chat_message_sender_room_fk_sql = IF(
    @has_chat_message_sender_room_fk = 0,
    'ALTER TABLE chat_messages ADD CONSTRAINT fk_chat_message_sender_room FOREIGN KEY (sender_id, chat_room_id) REFERENCES chat_participants (id, chat_room_id)',
    'SELECT 1'
);
PREPARE add_chat_message_sender_room_fk FROM @add_chat_message_sender_room_fk_sql;
EXECUTE add_chat_message_sender_room_fk;
DEALLOCATE PREPARE add_chat_message_sender_room_fk;

SET @has_chat_message_file_fk = (
    SELECT COUNT(*)
    FROM information_schema.key_column_usage
    WHERE table_schema = DATABASE()
      AND table_name = 'chat_messages'
      AND column_name = 'file_id'
      AND referenced_table_schema = DATABASE()
      AND referenced_table_name = 'files'
      AND referenced_column_name = 'id'
);
SET @add_chat_message_file_fk_sql = IF(
    @has_chat_message_file_fk = 0,
    'ALTER TABLE chat_messages ADD CONSTRAINT fk_chat_message_file FOREIGN KEY (file_id) REFERENCES files (id)',
    'SELECT 1'
);
PREPARE add_chat_message_file_fk FROM @add_chat_message_file_fk_sql;
EXECUTE add_chat_message_file_fk;
DEALLOCATE PREPARE add_chat_message_file_fk;
