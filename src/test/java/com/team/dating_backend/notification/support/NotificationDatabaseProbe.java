package com.team.dating_backend.notification.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.team.dating_backend.notification.dto.event.NotificationCreateRequested;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

public final class NotificationDatabaseProbe implements AutoCloseable {

    private final JdbcTemplate reader;
    private final JdbcTemplate observer;
    private final DataSource lockDataSource;
    private final String database;
    private final Deque<RowLock> locks = new ArrayDeque<>();

    public record StoredNotification(long id, String sourceEventId, long recipientUserId, String type,
        String title, String body, String targetType, Long targetId, LocalDateTime createdAt,
        LocalDateTime readAt, LocalDateTime deletedAt) {}

    public record LockWait(String transactionId, long threadId, long connectionId, long blockingConnectionId,
        String table, String index, String mode, String data, String query) {}

    public record InFlightInsert(long threadId, long connectionId, Long transactionEventId, String transactionState,
        String engineTransactionId, String statementState, String query) {}

    public NotificationDatabaseProbe(DataSource readDataSource, DataSource lockDataSource, String database) {
        reader = new JdbcTemplate(readDataSource);
        observer = new JdbcTemplate(lockDataSource);
        reader.setQueryTimeout(3);
        observer.setQueryTimeout(3);
        this.lockDataSource = lockDataSource;
        this.database = database;
    }

    public List<StoredNotification> notifications(NotificationCreateRequested request) {
        return List.copyOf(reader.query("""
            SELECT id, source_event_id, user_id, type, title, body, target_type, target_id,
                created_at, read_at, deleted_at
            FROM notifications WHERE source_event_id = ? AND user_id = ? AND type = ?
            """, (row, index) -> new StoredNotification(row.getLong("id"), row.getString("source_event_id"),
            row.getLong("user_id"), row.getString("type"), row.getString("title"), row.getString("body"),
            row.getString("target_type"), row.getObject("target_id", Long.class),
            row.getObject("created_at", LocalDateTime.class), row.getObject("read_at", LocalDateTime.class),
            row.getObject("deleted_at", LocalDateTime.class)),
            request.sourceEventId(), request.recipientUserId(), request.type().name()));
    }

    public RowLock lockRecipient(long userId) throws SQLException {
        return lock("users", "SELECT id FROM users WHERE id = ? FOR UPDATE", userId);
    }

    public List<InFlightInsert> inFlightInserts(NotificationCreateRequested request) {
        return observer
            .query(
                """
                    SELECT thread.THREAD_ID AS thread_id, thread.PROCESSLIST_ID AS connection_id,
                        transaction.EVENT_ID AS transaction_event_id, transaction.STATE AS transaction_state,
                        innodb.trx_id AS engine_transaction_id, thread.PROCESSLIST_STATE AS statement_state,
                        thread.PROCESSLIST_INFO AS query_text
                    FROM performance_schema.threads thread
                    LEFT JOIN performance_schema.events_transactions_current transaction ON transaction.THREAD_ID = thread.THREAD_ID
                    LEFT JOIN information_schema.innodb_trx innodb ON innodb.trx_mysql_thread_id = thread.PROCESSLIST_ID
                    WHERE thread.PROCESSLIST_DB = ? AND thread.PROCESSLIST_INFO IS NOT NULL
                    ORDER BY thread.THREAD_ID
                    """,
                (row, index) -> new InFlightInsert(row.getLong("thread_id"), row.getLong("connection_id"),
                    row.getObject("transaction_event_id", Long.class), row.getString("transaction_state"),
                    row.getString("engine_transaction_id"), row.getString("statement_state"),
                    row.getString("query_text")),
                database)
            .stream().filter(insert -> insert.query().stripLeading().toLowerCase(Locale.ROOT)
                .startsWith("insert into notifications ") && insert.query().contains(request.sourceEventId()))
            .toList();
    }

    public RowLock lockExistingNotification(NotificationCreateRequested request) throws SQLException {
        return lock("notifications", """
            SELECT id FROM notifications FORCE INDEX (uk_notifications_source_recipient_type)
            WHERE source_event_id = ? AND user_id = ? AND type = ? FOR UPDATE
            """, request.sourceEventId(), request.recipientUserId(), request.type().name());
    }

    private RowLock lock(String table, String sql, Object... parameters) throws SQLException {
        var connection = lockDataSource.getConnection();
        try {
            connection.setAutoCommit(false);
            long connectionId;
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(3);
                statement.execute("SET SESSION innodb_lock_wait_timeout = 3");
                try (var result = statement.executeQuery("SELECT CONNECTION_ID()")) {
                    assertThat(result.next()).isTrue();
                    connectionId = result.getLong(1);
                }
            }
            long rowId;
            try (var statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(3);
                for (int index = 0; index < parameters.length; index++) {
                    statement.setObject(index + 1, parameters[index]);
                }
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    rowId = result.getLong(1);
                    assertThat(result.next()).isFalse();
                }
            }
            var lock = new RowLock(connection, connectionId, rowId, table);
            locks.addFirst(lock);
            return lock;
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public final class RowLock implements AutoCloseable {
        private final Connection connection;
        private final long connectionId;
        private final long rowId;
        private final String table;
        private boolean released;

        private RowLock(Connection connection, long connectionId, long rowId, String table) {
            this.connection = connection;
            this.connectionId = connectionId;
            this.rowId = rowId;
            this.table = table;
        }

        public long connectionId() {
            return connectionId;
        }

        public long rowId() {
            return rowId;
        }

        public List<LockWait> waitingTransactions() {
            var waiting = observer.query("""
                SELECT waits.REQUESTING_ENGINE_TRANSACTION_ID AS transaction_id,
                    waits.REQUESTING_THREAD_ID AS thread_id, waiter.PROCESSLIST_ID AS connection_id,
                    holder.PROCESSLIST_ID AS blocking_connection_id,
                    requested.OBJECT_NAME AS table_name, requested.INDEX_NAME AS index_name,
                    requested.LOCK_MODE AS lock_mode, requested.LOCK_DATA AS lock_data,
                    waiter.PROCESSLIST_INFO AS query_text
                FROM performance_schema.data_lock_waits waits
                JOIN performance_schema.data_locks requested
                    ON requested.ENGINE = waits.ENGINE
                    AND requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                JOIN performance_schema.threads holder ON holder.THREAD_ID = waits.BLOCKING_THREAD_ID
                JOIN performance_schema.threads waiter ON waiter.THREAD_ID = waits.REQUESTING_THREAD_ID
                WHERE requested.OBJECT_SCHEMA = ?
                ORDER BY waits.REQUESTING_THREAD_ID, requested.ENGINE_LOCK_ID
                """, (row, index) -> new LockWait(row.getString("transaction_id"), row.getLong("thread_id"),
                row.getLong("connection_id"), row.getLong("blocking_connection_id"),
                row.getString("table_name"), row.getString("index_name"),
                row.getString("lock_mode"), row.getString("lock_data"), row.getString("query_text")),
                database);
            var blockingConnections = new HashSet<Long>();
            blockingConnections.add(connectionId);
            var connected = new LinkedHashSet<LockWait>();
            boolean changed;
            do {
                changed = false;
                for (var wait : waiting) {
                    if (blockingConnections.contains(wait.blockingConnectionId())
                        && (wait.blockingConnectionId() != connectionId || wait.table().equals(table))) {
                        connected.add(wait);
                        changed |= blockingConnections.add(wait.connectionId());
                    }
                }
            } while (changed);
            return List.copyOf(connected);
        }

        public List<LockWait> awaitWaitingTransactions(int count) {
            assertThat(released).isFalse();
            assertThat(count).isPositive();
            return await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .until(this::waitingTransactions,
                    waits -> waits.stream().map(LockWait::transactionId).distinct().count() >= count);
        }

        @Override
        public void close() throws SQLException {
            if (!released) {
                released = true;
                try (connection) {
                    connection.rollback();
                }
            }
        }
    }

    @Override
    public void close() throws SQLException {
        SQLException failure = null;
        while (!locks.isEmpty()) {
            try {
                locks.removeFirst().close();
            } catch (SQLException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
