package de.ethria.trackTimer.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

/** Applies idempotent schema migrations to existing TrackTimer databases. */
public final class DatabaseMigrator {
    private DatabaseMigrator() { }

    public static void migrateEventCooldown(Connection connection, int defaultSeconds) throws SQLException {
        int safeDefault = Math.max(0, Math.min(86400, defaultSeconds));
        if (!hasColumn(connection, "events", "cooldown_seconds")) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE events ADD COLUMN cooldown_seconds INTEGER NOT NULL DEFAULT " + safeDefault);
            }
        }
        if (!hasColumn(connection, "events", "cooldown_until")) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE events ADD COLUMN cooldown_until BIGINT NOT NULL DEFAULT 0");
            }
        }
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery("SELECT * FROM " + table + " LIMIT 0")) {
            ResultSetMetaData metadata = columns.getMetaData();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                if (column.equalsIgnoreCase(metadata.getColumnLabel(index))) return true;
            }
            return false;
        }
    }
}
