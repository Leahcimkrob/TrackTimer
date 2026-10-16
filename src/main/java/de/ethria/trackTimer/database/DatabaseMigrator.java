package de.ethria.trackTimer.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.PreparedStatement;
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

    public static void migrateTrafficLightStartMode(Connection connection, int defaultCooldownSeconds) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT);
        if (product.contains("sqlite")) {
            String createSql = null;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'events'");
                 ResultSet rows = statement.executeQuery()) {
                if (rows.next()) createSql = rows.getString(1);
            }
            if (createSql == null || createSql.toLowerCase(java.util.Locale.ROOT).contains("traffic_light")) return;
            boolean foreignKeys;
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("PRAGMA foreign_keys")) {
                foreignKeys = rows.next() && rows.getInt(1) != 0;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = OFF");
            }
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE events_traffic_light_migration ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, event_name VARCHAR(128) NOT NULL UNIQUE, "
                        + "laps INTEGER NOT NULL, start_mode VARCHAR(16) NOT NULL DEFAULT 'player', "
                        + "cooldown_seconds INTEGER NOT NULL DEFAULT " + Math.max(0, Math.min(86400, defaultCooldownSeconds))
                        + ", cooldown_until BIGINT NOT NULL DEFAULT 0, "
                        + "icon TEXT NOT NULL DEFAULT 'stone', created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                        + "CHECK (laps > 0), CHECK (start_mode IN ('player', 'signal', 'traffic_light'))) ");
                statement.execute("INSERT INTO events_traffic_light_migration "
                        + "(id, event_name, laps, start_mode, cooldown_seconds, cooldown_until, icon, created_at) "
                        + "SELECT id, event_name, laps, start_mode, cooldown_seconds, cooldown_until, icon, created_at FROM events");
                statement.execute("DROP TABLE events");
                statement.execute("ALTER TABLE events_traffic_light_migration RENAME TO events");
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
                if (foreignKeys) try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA foreign_keys = ON");
                }
            }
            return;
        }

        if (product.contains("mysql") || product.contains("maria")) {
            String constraint = null;
            String clause = null;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT tc.CONSTRAINT_NAME, cc.CHECK_CLAUSE
                    FROM information_schema.TABLE_CONSTRAINTS tc
                    JOIN information_schema.CHECK_CONSTRAINTS cc
                      ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
                     AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
                    WHERE tc.TABLE_SCHEMA = DATABASE() AND tc.TABLE_NAME = 'events'
                      AND tc.CONSTRAINT_TYPE = 'CHECK' AND cc.CHECK_CLAUSE LIKE '%start_mode%'
                    """); ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    constraint = rows.getString(1);
                    clause = rows.getString(2);
                }
            }
            if (constraint != null && (clause == null
                    || !clause.toLowerCase(java.util.Locale.ROOT).contains("traffic_light"))) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE events DROP CHECK `" + constraint.replace("`", "``") + "`");
                    statement.execute("ALTER TABLE events ADD CONSTRAINT chk_events_start_mode "
                            + "CHECK (start_mode IN ('player', 'signal', 'traffic_light'))");
                }
            }
        }
    }

    /** Stores the five red-light note pitches and the start note on each track. */
    public static void migrateTrafficLightSounds(Connection connection) throws SQLException {
        String[][] columns = {
                {"traffic_light_sound", "VARCHAR(64) NOT NULL DEFAULT 'BLOCK_NOTE_BLOCK_BIT'"},
                {"traffic_light_red_pitch_1", "INTEGER NOT NULL DEFAULT 6"},
                {"traffic_light_red_pitch_2", "INTEGER NOT NULL DEFAULT 8"},
                {"traffic_light_red_pitch_3", "INTEGER NOT NULL DEFAULT 10"},
                {"traffic_light_red_pitch_4", "INTEGER NOT NULL DEFAULT 11"},
                {"traffic_light_red_pitch_5", "INTEGER NOT NULL DEFAULT 11"},
                {"traffic_light_go_pitch", "INTEGER NOT NULL DEFAULT 18"}
        };
        for (String[] column : columns) {
            if (hasColumn(connection, "events", column[0])) continue;
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE events ADD COLUMN " + column[0] + " " + column[1]);
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
