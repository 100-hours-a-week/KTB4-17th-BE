package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V37__normalize_notification_outbox_cutover extends BaseJavaMigration {

    private static final String TABLE_NAME = "notification_request_outbox";
    private static final String STATUS_CONSTRAINT = "chk_notification_outbox_status";

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        ensureCompletedAtColumn(connection);
        requeuePublishedRows(connection);
        replaceStatusConstraint(connection);
        dropPublishedAtColumn(connection);
    }

    private void ensureCompletedAtColumn(Connection connection) throws Exception {
        if (!columnExists(connection, "completed_at")) {
            execute(connection, "ALTER TABLE `notification_request_outbox` "
                + "ADD COLUMN `completed_at` datetime(6) DEFAULT NULL");
        }
    }

    private void requeuePublishedRows(Connection connection) throws Exception {
        execute(connection, "UPDATE `notification_request_outbox` "
            + "SET `status` = 'PENDING', `failure_count` = 0, `last_failure_type` = NULL, "
            + "`next_attempt_at` = UTC_TIMESTAMP(6), "
            + "`processing_token` = NULL, `processing_until` = NULL, `completed_at` = NULL "
            + "WHERE `status` = 'PUBLISHED'");
    }

    private void replaceStatusConstraint(Connection connection) throws Exception {
        String addConstraint = "ADD CONSTRAINT `chk_notification_outbox_status` "
            + "CHECK (`status` IN ('PENDING', 'FAILED', 'COMPLETED'))";
        String alteration = constraintExists(connection)
            ? "ALTER TABLE `notification_request_outbox` DROP CHECK `chk_notification_outbox_status`, "
                + addConstraint
            : "ALTER TABLE `notification_request_outbox` " + addConstraint;
        execute(connection, alteration);
    }

    private void dropPublishedAtColumn(Connection connection) throws Exception {
        if (columnExists(connection, "published_at")) {
            execute(connection, "ALTER TABLE `notification_request_outbox` DROP COLUMN `published_at`");
        }
    }

    private boolean columnExists(Connection connection, String columnName) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = ?
              AND column_name = ?
            LIMIT 1
            """)) {
            statement.setString(1, TABLE_NAME);
            statement.setString(2, columnName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean constraintExists(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT 1
            FROM information_schema.table_constraints
            WHERE constraint_schema = DATABASE()
              AND table_name = ?
              AND constraint_name = ?
              AND constraint_type = 'CHECK'
            LIMIT 1
            """)) {
            statement.setString(1, TABLE_NAME);
            statement.setString(2, STATUS_CONSTRAINT);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
