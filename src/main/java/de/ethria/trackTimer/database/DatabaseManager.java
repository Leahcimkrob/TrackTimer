package de.ethria.trackTimer.database;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

public final class DatabaseManager {
    private static final java.util.regex.Pattern EVENT_NAME_PATTERN =
            java.util.regex.Pattern.compile("[\\p{L}\\p{N}_-]+");

    private final JavaPlugin plugin;
    private final Connection connection;
    private final String autoIncrement;

    public DatabaseManager(JavaPlugin plugin) throws SQLException {
        this.plugin = plugin;
        FileConfiguration config = plugin.getConfig();
        String type = config.getString("database.type", "sqlite").trim().toLowerCase(Locale.ROOT);

        if ("sqlite".equals(type)) {
            File databaseFile = new File(plugin.getDataFolder(),
                    config.getString("database.sqlite.file", "tracktimer.db"));
            String parent = databaseFile.getParent();
            if (parent != null && !new File(parent).isDirectory() && !new File(parent).mkdirs()) {
                throw new SQLException("Could not create SQLite database directory: " + parent);
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
            autoIncrement = "INTEGER PRIMARY KEY AUTOINCREMENT";
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
            }
        } else if ("mysql".equals(type)) {
            String host = config.getString("database.mysql.host", "localhost");
            int port = config.getInt("database.mysql.port", 3306);
            String name = config.getString("database.mysql.database", "tracktimer");
            String url = "jdbc:mysql://" + host + ":" + port + "/" + name
                    + "?useSSL=" + config.getBoolean("database.mysql.useSSL", true)
                    + "&serverTimezone=UTC";
            connection = DriverManager.getConnection(
                    url,
                    config.getString("database.mysql.username", "root"),
                    config.getString("database.mysql.password", "")
            );
            autoIncrement = "BIGINT PRIMARY KEY AUTO_INCREMENT";
        } else {
            throw new IllegalArgumentException("Unsupported database.type '" + type + "'. Use sqlite or mysql.");
        }
    }

    public void initialize() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : tableStatements()) {
                statement.executeUpdate(sql);
            }
        }
        createIndexes();
        plugin.getLogger().info("Database tables are ready.");
    }

    /**
     * Inserts an event, returning {@code false} if its unique name is already
     * in use.
     */
    public boolean createEvent(String eventName, int laps, String startMode) throws SQLException {
        if (!isValidEventName(eventName)) {
            throw new IllegalArgumentException("Event names must contain 1 to 128 letters, digits, underscores, or hyphens.");
        }
        if (laps < 1) {
            throw new IllegalArgumentException("Event laps must be greater than zero.");
        }
        if (!List.of("player", "signal").contains(startMode)) {
            throw new IllegalArgumentException("Start mode must be 'player' or 'signal'.");
        }

        String sql = "INSERT INTO events (event_name, laps, start_mode) VALUES (?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, eventName);
            statement.setInt(2, laps);
            statement.setString(3, startMode);
            statement.executeUpdate();
            return true;
        } catch (SQLException exception) {
            if (isUniqueConstraintViolation(exception)) {
                return false;
            }
            throw exception;
        }
    }

    public static boolean isValidEventName(String eventName) {
        return eventName != null
                && eventName.codePointCount(0, eventName.length()) <= 128
                && EVENT_NAME_PATTERN.matcher(eventName).matches();
    }

    private boolean isUniqueConstraintViolation(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            if (current.getErrorCode() == 19
                    || current.getErrorCode() == 1062
                    || "23000".equals(current.getSQLState())
                    || "23505".equals(current.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    public void close() {
        try {
            connection.close();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not close the database connection: " + exception.getMessage());
        }
    }

    private List<String> tableStatements() {
        return List.of(
                """
                CREATE TABLE IF NOT EXISTS players (
                    uuid VARCHAR(36) PRIMARY KEY,
                    last_name VARCHAR(16) NOT NULL,
                    last_seen BIGINT NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS events (
                    id %s,
                    event_name VARCHAR(128) NOT NULL UNIQUE,
                    laps INTEGER NOT NULL,
                    start_mode VARCHAR(16) NOT NULL DEFAULT 'player',
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CHECK (laps > 0),
                    CHECK (start_mode IN ('player', 'signal'))
                )
                """.formatted(autoIncrement),
                """
                CREATE TABLE IF NOT EXISTS event_triggers (
                    id %s,
                    event_id BIGINT NOT NULL,
                    trigger_type VARCHAR(16) NOT NULL,
                    checkpoint_order INTEGER,
                    server VARCHAR(128) NOT NULL,
                    world VARCHAR(128) NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    block_type VARCHAR(100) NOT NULL,
                    FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE,
                    CHECK (trigger_type IN ('start', 'checkpoint', 'end')),
                    CHECK (
                        (trigger_type = 'checkpoint' AND checkpoint_order IS NOT NULL)
                        OR (trigger_type != 'checkpoint' AND checkpoint_order IS NULL)
                    ),
                    UNIQUE (event_id, trigger_type, checkpoint_order)
                )
                """.formatted(autoIncrement),
                """
                CREATE TABLE IF NOT EXISTS race_results (
                    id %s,
                    event_id BIGINT NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    start_time BIGINT NOT NULL,
                    end_time BIGINT,
                    race_time_ms BIGINT,
                    laps_completed INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE,
                    FOREIGN KEY (player_uuid) REFERENCES players(uuid),
                    CHECK (laps_completed >= 0)
                )
                """.formatted(autoIncrement),
                """
                CREATE TABLE IF NOT EXISTS race_checkpoint_times (
                    id %s,
                    race_result_id BIGINT NOT NULL,
                    trigger_id BIGINT NOT NULL,
                    lap INTEGER NOT NULL,
                    checkpoint_time_ms BIGINT NOT NULL,
                    FOREIGN KEY (race_result_id) REFERENCES race_results(id) ON DELETE CASCADE,
                    FOREIGN KEY (trigger_id) REFERENCES event_triggers(id),
                    CHECK (lap > 0),
                    UNIQUE (race_result_id, trigger_id, lap)
                )
                """.formatted(autoIncrement)
        );
    }

    private void createIndexes() throws SQLException {
        List<IndexDefinition> indexes = List.of(
                new IndexDefinition("event_triggers", "idx_event_triggers_event", "event_id"),
                new IndexDefinition("race_results", "idx_race_results_leaderboard", "event_id, race_time_ms"),
                new IndexDefinition("race_results", "idx_race_results_player", "player_uuid"),
                new IndexDefinition("race_checkpoint_times", "idx_checkpoint_times_result", "race_result_id")
        );

        for (IndexDefinition index : indexes) {
            if (indexExists(index.table(), index.name())) {
                continue;
            }
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE INDEX " + index.name() + " ON "
                        + index.table() + "(" + index.columns() + ")");
            }
        }
    }

    private boolean indexExists(String tableName, String indexName) throws SQLException {
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                connection.getCatalog(), null, tableName, false, false)) {
            while (indexes.next()) {
                if (indexName.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private record IndexDefinition(String table, String name, String columns) {
    }
}
