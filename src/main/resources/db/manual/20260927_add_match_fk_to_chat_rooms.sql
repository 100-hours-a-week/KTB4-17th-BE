-- Preflight: both queries should return no rows before applying this migration.
SELECT match_id, COUNT(*) AS room_count
FROM chat_rooms
GROUP BY match_id
HAVING COUNT(*) > 1;

SELECT chat_room.id, chat_room.match_id
FROM chat_rooms AS chat_room
LEFT JOIN matches AS match_record ON match_record.id = chat_room.match_id
WHERE match_record.id IS NULL;

-- The previous ChatRoom mapping already declared this unique key. Check the
-- schema before adding it so this migration also works on databases where it
-- is already present (or exists under another name).
SET @has_unique_chat_room_match = (
    SELECT COUNT(*)
    FROM (
        SELECT index_name
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'chat_rooms'
          AND non_unique = 0
        GROUP BY index_name
        HAVING COUNT(*) = 1
           AND SUM(column_name = 'match_id') = 1
    ) AS unique_match_indexes
);

SET @add_unique_chat_room_match_sql = IF(
    @has_unique_chat_room_match = 0,
    'ALTER TABLE chat_rooms ADD CONSTRAINT uk_chat_room_match UNIQUE (match_id)',
    'SELECT 1'
);
PREPARE add_unique_chat_room_match FROM @add_unique_chat_room_match_sql;
EXECUTE add_unique_chat_room_match;
DEALLOCATE PREPARE add_unique_chat_room_match;

-- A foreign key enforces that every room refers to an existing Match. It does
-- not enforce the reverse direction; Match-to-room creation is kept atomic in
-- LikeSendService's transaction.
SET @has_chat_room_match_fk = (
    SELECT COUNT(*)
    FROM (
        SELECT constraint_name
        FROM information_schema.key_column_usage
        WHERE table_schema = DATABASE()
          AND table_name = 'chat_rooms'
          AND referenced_table_schema = DATABASE()
          AND referenced_table_name = 'matches'
          AND referenced_column_name = 'id'
        GROUP BY constraint_name
        HAVING COUNT(*) = 1
           AND SUM(column_name = 'match_id') = 1
    ) AS matching_foreign_keys
);

SET @add_chat_room_match_fk_sql = IF(
    @has_chat_room_match_fk = 0,
    CONCAT(
        'ALTER TABLE chat_rooms ADD CONSTRAINT fk_chat_room_match ',
        'FOREIGN KEY (match_id) REFERENCES matches (id)'
    ),
    'SELECT 1'
);
PREPARE add_chat_room_match_fk FROM @add_chat_room_match_fk_sql;
EXECUTE add_chat_room_match_fk;
DEALLOCATE PREPARE add_chat_room_match_fk;
