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
import java.util.ArrayList;

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

    public List<Event> listEvents() throws SQLException {
        List<Event> events = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT e.id, e.event_name, e.laps, e.start_mode, e.icon, e.created_at,
                            (SELECT COUNT(*) FROM event_triggers t WHERE t.event_id = e.id) AS trigger_count
                     FROM events e ORDER BY e.event_name
                     """)) {
            while (rows.next()) {
                events.add(new Event(rows.getLong("id"), rows.getString("event_name"),
                        rows.getInt("laps"), rows.getString("start_mode"), rows.getString("icon"),
                        rows.getInt("trigger_count"), rows.getString("created_at")));
            }
        }
        return List.copyOf(events);
    }

    public record Event(long id, String name, int laps, String startMode, String icon, int triggerCount, String created) { }

    public void updateEventStartMode(long eventId, String startMode) throws SQLException {
        if (!List.of("player", "signal").contains(startMode)) {
            throw new IllegalArgumentException("Start mode must be 'player' or 'signal'.");
        }
        try (PreparedStatement statement = connection.prepareStatement("UPDATE events SET start_mode = ? WHERE id = ?")) {
            statement.setString(1, startMode);
            statement.setLong(2, eventId);
            statement.executeUpdate();
        }
    }

    /** Updates an event name, returning {@code false} when it is already in use. */
    public boolean updateEventName(long eventId, String eventName) throws SQLException {
        if (!isValidEventName(eventName)) {
            throw new IllegalArgumentException("Event names must contain 1 to 128 letters, digits, underscores, or hyphens.");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE events SET event_name = ? WHERE id = ?")) {
            statement.setString(1, eventName);
            statement.setLong(2, eventId);
            statement.executeUpdate();
            return true;
        } catch (SQLException exception) {
            if (isUniqueConstraintViolation(exception)) return false;
            throw exception;
        }
    }

    public List<String> listEventTriggers(long eventId) throws SQLException {
        List<String> triggers = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT trigger_type, checkpoint_order, server, world, x, y, z, block_type, trigger_mode
                FROM event_triggers WHERE event_id = ?
                ORDER BY CASE trigger_type WHEN 'start' THEN 0 WHEN 'checkpoint' THEN 1 ELSE 2 END,
                         checkpoint_order
                """)) {
            statement.setLong(1, eventId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String type = rows.getString("trigger_type");
                    int order = rows.getInt("checkpoint_order");
                    String orderLabel = rows.wasNull() ? "" : " #" + order;
                    triggers.add(type + orderLabel + " — " + rows.getString("server") + "/"
                            + rows.getString("world") + " " + rows.getInt("x") + ", "
                            + rows.getInt("y") + ", " + rows.getInt("z")
                            + " [" + rows.getString("trigger_mode") + ":" + rows.getString("block_type") + "]");
                }
            }
        }
        return List.copyOf(triggers);
    }

    public List<EventTrigger> listEventTriggerDetails(long eventId) throws SQLException {
        List<EventTrigger> triggers = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT trigger_type, checkpoint_order, server, world, x, y, z, block_type, trigger_mode
                FROM event_triggers WHERE event_id = ?
                ORDER BY CASE trigger_type WHEN 'start' THEN 0 WHEN 'checkpoint' THEN 1 ELSE 2 END,
                         checkpoint_order
                """)) {
            statement.setLong(1, eventId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    int order = rows.getInt("checkpoint_order");
                    Integer checkpointOrder = rows.wasNull() ? null : order;
                    triggers.add(new EventTrigger(rows.getString("trigger_type"), checkpointOrder,
                            rows.getString("server"), rows.getString("world"), rows.getInt("x"),
                            rows.getInt("y"), rows.getInt("z"), rows.getString("block_type"), rows.getString("trigger_mode")));
                }
            }
        }
        return List.copyOf(triggers);
    }

    public record EventTrigger(String type, Integer checkpointOrder, String server, String world,
                               int x, int y, int z, String blockType, String triggerMode) { }

    public int nextCheckpointOrder(long eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(checkpoint_order), 0) + 1 FROM event_triggers WHERE event_id = ? AND trigger_type = 'checkpoint'")) {
            statement.setLong(1, eventId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 1;
            }
        }
    }

    public boolean addStartTrigger(long eventId, String server, String world, int x, int y, int z,
                                   String blockType, String triggerMode) throws SQLException {
        return addBlockTrigger(eventId, "start", null, server, world, x, y, z, blockType, triggerMode);
    }

    public boolean addEndTrigger(long eventId, String server, String world, int x, int y, int z,
                                 String blockType, String triggerMode) throws SQLException {
        return addBlockTrigger(eventId, "end", null, server, world, x, y, z, blockType, triggerMode);
    }

    public boolean addCheckpointTrigger(long eventId, int checkpointOrder, String server, String world,
                                        int x, int y, int z, String blockType, String triggerMode) throws SQLException {
        if (checkpointOrder < 1) throw new IllegalArgumentException("Checkpoint order must be positive.");
        return addBlockTrigger(eventId, "checkpoint", checkpointOrder, server, world, x, y, z, blockType, triggerMode);
    }

    public boolean addRedstoneStartTrigger(long eventId, String server, String world, int x, int y, int z,
                                           String blockType) throws SQLException {
        return addBlockTrigger(eventId, "start", null, server, world, x, y, z, blockType, "REDSTONE_SIGNAL");
    }

    private boolean addBlockTrigger(long eventId, String type, Integer checkpointOrder, String server, String world, int x, int y, int z,
                                    String blockType, String triggerMode) throws SQLException {
        if (!List.of("BLOCK", "PRESSURE_PLATE", "REDSTONE_SIGNAL").contains(triggerMode)) {
            throw new IllegalArgumentException("Unsupported trigger mode: " + triggerMode);
        }
        if (hasBlockTrigger(eventId, type, triggerMode, server, world, x, y, z)) return false;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO event_triggers (event_id, trigger_type, checkpoint_order, server, world, x, y, z, block_type, trigger_mode)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, eventId);
            statement.setString(2, type);
            if (checkpointOrder == null) statement.setNull(3, java.sql.Types.INTEGER);
            else statement.setInt(3, checkpointOrder);
            statement.setString(4, server);
            statement.setString(5, world);
            statement.setInt(6, x);
            statement.setInt(7, y);
            statement.setInt(8, z);
            statement.setString(9, blockType);
            statement.setString(10, triggerMode);
            return statement.executeUpdate() > 0;
        }
    }

    private boolean hasBlockTrigger(long eventId, String type, String triggerMode, String server, String world,
                                    int x, int y, int z) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM event_triggers
                WHERE event_id = ? AND trigger_type = ? AND trigger_mode = ? AND server = ? AND world = ?
                  AND x = ? AND y = ? AND z = ?
                LIMIT 1
                """)) {
            statement.setLong(1, eventId);
            statement.setString(2, type);
            statement.setString(3, triggerMode);
            statement.setString(4, server);
            statement.setString(5, world);
            statement.setInt(6, x);
            statement.setInt(7, y);
            statement.setInt(8, z);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    public boolean removeStartTrigger(long eventId, String server, String world, int x, int y, int z)
            throws SQLException {
        return removeBlockTrigger(eventId, "start", "BLOCK_OR_PRESSURE_PLATE", server, world, x, y, z);
    }

    public boolean removeEndTrigger(long eventId, String server, String world, int x, int y, int z)
            throws SQLException {
        return removeBlockTrigger(eventId, "end", null, server, world, x, y, z);
    }

    public boolean removeCheckpointTrigger(long eventId, String server, String world, int x, int y, int z)
            throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM race_checkpoint_times WHERE trigger_id IN (
                        SELECT id FROM event_triggers WHERE event_id = ? AND trigger_type = 'checkpoint'
                          AND server = ? AND world = ? AND x = ? AND y = ? AND z = ?
                    )
                    """)) {
                statement.setLong(1, eventId);
                statement.setString(2, server);
                statement.setString(3, world);
                statement.setInt(4, x);
                statement.setInt(5, y);
                statement.setInt(6, z);
                statement.executeUpdate();
            }
            boolean removed = removeBlockTrigger(eventId, "checkpoint", null, server, world, x, y, z);
            connection.commit();
            return removed;
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    public boolean removeRedstoneStartTrigger(long eventId, String server, String world, int x, int y, int z)
            throws SQLException {
        return removeBlockTrigger(eventId, "start", "REDSTONE_SIGNAL", server, world, x, y, z);
    }

    private boolean removeBlockTrigger(long eventId, String type, String triggerMode, String server, String world, int x, int y, int z)
            throws SQLException {
        String modeClause = triggerMode == null ? ""
                : "BLOCK_OR_PRESSURE_PLATE".equals(triggerMode)
                ? " AND trigger_mode <> 'REDSTONE_SIGNAL'" : " AND trigger_mode = ?";
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM event_triggers WHERE event_id = ? AND trigger_type = ?"
                + modeClause + " AND server = ? AND world = ? AND x = ? AND y = ? AND z = ?")) {
            statement.setLong(1, eventId);
            statement.setString(2, type);
            int index = 3;
            if (triggerMode != null && !"BLOCK_OR_PRESSURE_PLATE".equals(triggerMode)) statement.setString(index++, triggerMode);
            statement.setString(index++, server);
            statement.setString(index++, world);
            statement.setInt(index++, x);
            statement.setInt(index++, y);
            statement.setInt(index, z);
            return statement.executeUpdate() > 0;
        }
    }

    public void updateEventLaps(long eventId, int laps) throws SQLException {
        if (laps < 1) throw new IllegalArgumentException("Event laps must be greater than zero.");
        try (PreparedStatement statement = connection.prepareStatement("UPDATE events SET laps = ? WHERE id = ?")) {
            statement.setInt(1, laps);
            statement.setLong(2, eventId);
            statement.executeUpdate();
        }
    }

    public void updateEventIcon(long eventId, String icon) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE events SET icon = ? WHERE id = ?")) {
            statement.setString(1, icon);
            statement.setLong(2, eventId);
            statement.executeUpdate();
        }
    }

    public void deleteEvent(long eventId) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            executeDelete("DELETE FROM race_checkpoint_times WHERE race_result_id IN (SELECT id FROM race_results WHERE event_id = ?)", eventId);
            executeDelete("DELETE FROM race_checkpoint_times WHERE trigger_id IN (SELECT id FROM event_triggers WHERE event_id = ?)", eventId);
            executeDelete("DELETE FROM race_results WHERE event_id = ?", eventId);
            executeDelete("DELETE FROM event_triggers WHERE event_id = ?", eventId);
            executeDelete("DELETE FROM events WHERE id = ?", eventId);
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private void executeDelete(String sql, long eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, eventId);
            statement.executeUpdate();
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
                    icon TEXT NOT NULL DEFAULT 'stone',
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
                    trigger_mode VARCHAR(32) NOT NULL DEFAULT 'BLOCK',
                    FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE,
                    CHECK (trigger_type IN ('start', 'checkpoint', 'end')),
                    CHECK (trigger_mode IN ('BLOCK', 'PRESSURE_PLATE', 'REDSTONE_SIGNAL')),
                    CHECK (
                        (trigger_type = 'checkpoint' AND checkpoint_order IS NOT NULL)
                        OR (trigger_type != 'checkpoint' AND checkpoint_order IS NULL)
                    )
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
