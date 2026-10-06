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
import java.sql.ResultSetMetaData;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public final class DatabaseManager {
    private static final java.util.regex.Pattern EVENT_NAME_PATTERN =
            java.util.regex.Pattern.compile("[\\p{L}\\p{N}_-]+");

    private final JavaPlugin plugin;
    private final Connection connection;
    private final String autoIncrement;
    private final List<RaceHologramBoard> duplicateRaceHologramBoards = new ArrayList<>();

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
        ensureRaceResultsRedstoneColumn();
        dropLegacyRaceHologramUniqueIndex(connection);
        removeDuplicateRaceHolograms(connection, duplicateRaceHologramBoards);
        createIndexes();
        plugin.getLogger().info("Database tables are ready.");
    }

    /** Adds the redstone marker to race_results on databases created by earlier plugin versions. */
    private void ensureRaceResultsRedstoneColumn() throws SQLException {
        boolean columnExists = false;
        try (Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery("SELECT * FROM race_results LIMIT 0")) {
            ResultSetMetaData metadata = columns.getMetaData();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                if ("is_redstone".equalsIgnoreCase(metadata.getColumnLabel(index))) {
                    columnExists = true;
                    break;
                }
            }
        }
        if (!columnExists) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE race_results ADD COLUMN is_redstone BOOLEAN NOT NULL DEFAULT FALSE");
            }
        }
    }

    /** Copies all TrackTimer data between the configured SQLite and MySQL databases. */
    public void convert(String from, String to) throws SQLException {
        String sourceType = normalizeDatabaseType(from);
        String targetType = normalizeDatabaseType(to);
        if (sourceType.equals(targetType)) throw new IllegalArgumentException("Source and target databases must differ.");

        try (Connection source = openConnection(sourceType);
             Connection target = openConnection(targetType)) {
            String targetAutoIncrement = "sqlite".equals(targetType)
                    ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "BIGINT PRIMARY KEY AUTO_INCREMENT";
            try (Statement statement = target.createStatement()) {
                for (String sql : tableStatements(targetAutoIncrement)) statement.executeUpdate(sql);
                if ("sqlite".equals(targetType)) statement.execute("PRAGMA foreign_keys = ON");
            }
            target.setAutoCommit(false);
            try {
                for (String table : List.of("players", "events", "event_triggers", "race_sessions",
                        "race_results", "race_checkpoint_times", "race_lap_times", "race_session_results",
                        "race_holograms")) {
                    if ("race_holograms".equals(table) && !tableExists(source, table)) continue;
                    copyTable(source, target, table);
                }
                removeDuplicateRaceHolograms(target, null);
                target.commit();
            } catch (SQLException exception) {
                target.rollback();
                throw exception;
            } finally {
                target.setAutoCommit(true);
            }
            createIndexes(target);
        }
    }

    private Connection openConnection(String type) throws SQLException {
        FileConfiguration config = plugin.getConfig();
        if ("sqlite".equals(type)) {
            File file = new File(plugin.getDataFolder(), config.getString("database.sqlite.file", "tracktimer.db"));
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                throw new SQLException("Could not create SQLite database directory: " + parent);
            return DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        }
        String url = "jdbc:mysql://" + config.getString("database.mysql.host", "localhost") + ":"
                + config.getInt("database.mysql.port", 3306) + "/" + config.getString("database.mysql.database", "tracktimer")
                + "?useSSL=" + config.getBoolean("database.mysql.useSSL", true) + "&serverTimezone=UTC";
        return DriverManager.getConnection(url, config.getString("database.mysql.username", "root"),
                config.getString("database.mysql.password", ""));
    }

    private String normalizeDatabaseType(String type) {
        String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (!List.of("sqlite", "mysql").contains(normalized))
            throw new IllegalArgumentException("Database type must be sqlite or mysql.");
        return normalized;
    }

    private void copyTable(Connection source, Connection target, String table) throws SQLException {
        try (Statement query = source.createStatement(); ResultSet rows = query.executeQuery("SELECT * FROM " + table)) {
            ResultSetMetaData metadata = rows.getMetaData();
            int columns = metadata.getColumnCount();
            List<String> columnNames = new ArrayList<>();
            for (int i = 1; i <= columns; i++) columnNames.add(metadata.getColumnName(i));
            String names = String.join(", ", columnNames);
            String placeholders = String.join(", ", java.util.Collections.nCopies(columns, "?"));
            try (PreparedStatement insert = target.prepareStatement("INSERT INTO " + table + " (" + names + ") VALUES (" + placeholders + ")")) {
                while (rows.next()) {
                    for (int i = 1; i <= columns; i++) insert.setObject(i, rows.getObject(i));
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
    }

    private boolean tableExists(Connection database, String table) throws SQLException {
        try (ResultSet tables = database.getMetaData().getTables(database.getCatalog(), null, table, new String[]{"TABLE"})) {
            if (tables.next()) return true;
        }
        try (ResultSet tables = database.getMetaData().getTables(database.getCatalog(), null,
                table.toUpperCase(Locale.ROOT), new String[]{"TABLE"})) {
            return tables.next();
        }
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

    public Event getEvent(long eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.id, e.event_name, e.laps, e.start_mode, e.icon, e.created_at,
                       (SELECT COUNT(*) FROM event_triggers t WHERE t.event_id = e.id) AS trigger_count
                FROM events e WHERE e.id = ?
                """)) {
            statement.setLong(1, eventId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new Event(rows.getLong("id"), rows.getString("event_name"),
                        rows.getInt("laps"), rows.getString("start_mode"), rows.getString("icon"),
                        rows.getInt("trigger_count"), rows.getString("created_at"));
            }
        }
    }

    public List<RaceStatisticsEntry> listCompletedRaceStatistics(long eventId) throws SQLException {
        List<RaceStatisticsEntry> entries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT rr.id, e.event_name, rr.player_uuid, p.last_name, rr.race_time_ms,
                       rr.laps_completed, rr.start_time, rr.is_redstone
                FROM race_results rr
                JOIN events e ON e.id = rr.event_id
                JOIN players p ON p.uuid = rr.player_uuid
                WHERE rr.event_id = ? AND rr.end_time IS NOT NULL AND rr.race_time_ms IS NOT NULL
                """)) {
            statement.setLong(1, eventId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    entries.add(new RaceStatisticsEntry(rows.getLong("id"), eventId,
                            rows.getString("event_name"), rows.getString("player_uuid"),
                            rows.getString("last_name"), rows.getLong("race_time_ms"),
                            rows.getInt("laps_completed"), rows.getLong("start_time"),
                            rows.getBoolean("is_redstone")));
                }
            }
        }
        return List.copyOf(entries);
    }

    public List<RaceStatisticsEntry> listPlayerRaceStatistics(long eventId, String playerUuid) throws SQLException {
        List<RaceStatisticsEntry> entries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT rr.id, e.event_name, rr.player_uuid, p.last_name, rr.race_time_ms,
                       rr.laps_completed, rr.start_time, rr.is_redstone
                FROM race_results rr
                JOIN events e ON e.id = rr.event_id
                JOIN players p ON p.uuid = rr.player_uuid
                WHERE rr.event_id = ? AND rr.player_uuid = ?
                  AND rr.end_time IS NOT NULL AND rr.race_time_ms IS NOT NULL
                ORDER BY rr.start_time DESC, rr.id DESC
                """)) {
            statement.setLong(1, eventId);
            statement.setString(2, playerUuid);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    entries.add(new RaceStatisticsEntry(rows.getLong("id"), eventId,
                            rows.getString("event_name"), rows.getString("player_uuid"),
                            rows.getString("last_name"), rows.getLong("race_time_ms"),
                            rows.getInt("laps_completed"), rows.getLong("start_time"),
                            rows.getBoolean("is_redstone")));
                }
            }
        }
        return List.copyOf(entries);
    }

    public record Event(long id, String name, int laps, String startMode, String icon, int triggerCount, String created) { }
    public record RaceStatisticsEntry(long resultId, long eventId, String eventName, String playerUuid,
                                      String playerName, long raceTimeMillis, int lapsCompleted,
                                      long startTimeMillis, boolean redstoneStart) { }

    public record RaceLapTime(int lap, long lapTimeMillis) { }
    public record PlayerRaceLapTime(long raceResultId, int lap, long lapTimeMillis) { }
    public record RaceCheckpointSplit(int lap, int checkpointOrder, long elapsedMillis) { }

    public record RaceHologramBoard(String id, long eventId, boolean redstone, String server, String world,
                                    int lowX, int lowY, int lowZ, int highX, int highY, int highZ,
                                    double centerX, double topY, double centerZ, float yaw) { }

    public RaceHologramBoard saveRaceHologramBoard(RaceHologramBoard board) throws SQLException {
        String existingId = null;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM race_holograms
                WHERE server = ? AND event_id = ? AND redstone = ? AND world = ?
                  AND low_x = ? AND low_y = ? AND low_z = ?
                  AND high_x = ? AND high_y = ? AND high_z = ?
                """)) {
            statement.setString(1, board.server());
            statement.setLong(2, board.eventId());
            statement.setBoolean(3, board.redstone());
            statement.setString(4, board.world());
            statement.setInt(5, board.lowX());
            statement.setInt(6, board.lowY());
            statement.setInt(7, board.lowZ());
            statement.setInt(8, board.highX());
            statement.setInt(9, board.highY());
            statement.setInt(10, board.highZ());
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) existingId = rows.getString("id");
            }
        }
        if (existingId != null) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE race_holograms SET world = ?, low_x = ?, low_y = ?, low_z = ?,
                        high_x = ?, high_y = ?, high_z = ?, center_x = ?, top_y = ?, center_z = ?, yaw = ?
                    WHERE id = ?
                    """)) {
                statement.setString(1, board.world());
                statement.setInt(2, board.lowX());
                statement.setInt(3, board.lowY());
                statement.setInt(4, board.lowZ());
                statement.setInt(5, board.highX());
                statement.setInt(6, board.highY());
                statement.setInt(7, board.highZ());
                statement.setDouble(8, board.centerX());
                statement.setDouble(9, board.topY());
                statement.setDouble(10, board.centerZ());
                statement.setFloat(11, board.yaw());
                statement.setString(12, existingId);
                statement.executeUpdate();
            }
            return new RaceHologramBoard(existingId, board.eventId(), board.redstone(), board.server(), board.world(),
                    board.lowX(), board.lowY(), board.lowZ(), board.highX(), board.highY(), board.highZ(),
                    board.centerX(), board.topY(), board.centerZ(), board.yaw());
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO race_holograms (id, event_id, redstone, server, world,
                    low_x, low_y, low_z, high_x, high_y, high_z, center_x, top_y, center_z, yaw)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, board.id());
            statement.setLong(2, board.eventId());
            statement.setBoolean(3, board.redstone());
            statement.setString(4, board.server());
            statement.setString(5, board.world());
            statement.setInt(6, board.lowX());
            statement.setInt(7, board.lowY());
            statement.setInt(8, board.lowZ());
            statement.setInt(9, board.highX());
            statement.setInt(10, board.highY());
            statement.setInt(11, board.highZ());
            statement.setDouble(12, board.centerX());
            statement.setDouble(13, board.topY());
            statement.setDouble(14, board.centerZ());
            statement.setFloat(15, board.yaw());
            statement.executeUpdate();
        }
        return board;
    }

    public List<RaceHologramBoard> duplicateRaceHologramBoards() {
        return List.copyOf(duplicateRaceHologramBoards);
    }

    public List<RaceHologramBoard> listRaceHologramBoards(String server) throws SQLException {
        List<RaceHologramBoard> boards = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, event_id, redstone, server, world, low_x, low_y, low_z,
                       high_x, high_y, high_z, center_x, top_y, center_z, yaw
                FROM race_holograms WHERE server = ? ORDER BY id
                """)) {
            statement.setString(1, server);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) boards.add(new RaceHologramBoard(rows.getString("id"),
                        rows.getLong("event_id"), rows.getBoolean("redstone"), rows.getString("server"),
                        rows.getString("world"), rows.getInt("low_x"), rows.getInt("low_y"),
                        rows.getInt("low_z"), rows.getInt("high_x"), rows.getInt("high_y"),
                        rows.getInt("high_z"), rows.getDouble("center_x"), rows.getDouble("top_y"),
                        rows.getDouble("center_z"), rows.getFloat("yaw")));
            }
        }
        return List.copyOf(boards);
    }

    public List<RaceHologramBoard> listRaceHologramBoardsAt(String server, String world,
                                                            int x, int y, int z) throws SQLException {
        List<RaceHologramBoard> boards = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, event_id, redstone, server, world, low_x, low_y, low_z,
                       high_x, high_y, high_z, center_x, top_y, center_z, yaw
                FROM race_holograms
                WHERE server = ? AND world = ?
                  AND low_x <= ? AND high_x >= ?
                  AND low_y <= ? AND high_y >= ?
                  AND low_z <= ? AND high_z >= ?
                ORDER BY event_id, redstone
                """)) {
            statement.setString(1, server);
            statement.setString(2, world);
            statement.setInt(3, x);
            statement.setInt(4, x);
            statement.setInt(5, y);
            statement.setInt(6, y);
            statement.setInt(7, z);
            statement.setInt(8, z);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) boards.add(new RaceHologramBoard(rows.getString("id"),
                        rows.getLong("event_id"), rows.getBoolean("redstone"), rows.getString("server"),
                        rows.getString("world"), rows.getInt("low_x"), rows.getInt("low_y"),
                        rows.getInt("low_z"), rows.getInt("high_x"), rows.getInt("high_y"),
                        rows.getInt("high_z"), rows.getDouble("center_x"), rows.getDouble("top_y"),
                        rows.getDouble("center_z"), rows.getFloat("yaw")));
            }
        }
        return List.copyOf(boards);
    }

    public boolean deleteRaceHologramBoard(String id, String server) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM race_holograms WHERE id = ? AND server = ?")) {
            statement.setString(1, id);
            statement.setString(2, server);
            return statement.executeUpdate() > 0;
        }
    }

    public boolean updateRaceHologramPosition(String id, String server, double centerX,
                                              double topY, double centerZ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE race_holograms SET center_x = ?, top_y = ?, center_z = ?
                WHERE id = ? AND server = ?
                """)) {
            statement.setDouble(1, centerX);
            statement.setDouble(2, topY);
            statement.setDouble(3, centerZ);
            statement.setString(4, id);
            statement.setString(5, server);
            return statement.executeUpdate() > 0;
        }
    }

    private void removeDuplicateRaceHolograms(Connection database, List<RaceHologramBoard> removed)
            throws SQLException {
        Set<String> seen = new HashSet<>();
        List<RaceHologramBoard> duplicates = new ArrayList<>();
        try (Statement statement = database.createStatement(); ResultSet rows = statement.executeQuery("""
                SELECT id, event_id, redstone, server, world, low_x, low_y, low_z,
                       high_x, high_y, high_z, center_x, top_y, center_z, yaw
                FROM race_holograms ORDER BY server, event_id, redstone, id
                """)) {
            while (rows.next()) {
                RaceHologramBoard board = new RaceHologramBoard(rows.getString("id"),
                        rows.getLong("event_id"), rows.getBoolean("redstone"), rows.getString("server"),
                        rows.getString("world"), rows.getInt("low_x"), rows.getInt("low_y"),
                        rows.getInt("low_z"), rows.getInt("high_x"), rows.getInt("high_y"),
                        rows.getInt("high_z"), rows.getDouble("center_x"), rows.getDouble("top_y"),
                        rows.getDouble("center_z"), rows.getFloat("yaw"));
                String key = board.server() + '\u0000' + board.eventId() + '\u0000' + board.redstone()
                        + '\u0000' + board.world() + '\u0000' + board.lowX() + '\u0000' + board.lowY()
                        + '\u0000' + board.lowZ() + '\u0000' + board.highX() + '\u0000' + board.highY()
                        + '\u0000' + board.highZ();
                if (!seen.add(key)) duplicates.add(board);
            }
        }
        if (duplicates.isEmpty()) return;
        try (PreparedStatement delete = database.prepareStatement("DELETE FROM race_holograms WHERE id = ?")) {
            for (RaceHologramBoard duplicate : duplicates) {
                delete.setString(1, duplicate.id());
                delete.addBatch();
                if (removed != null) removed.add(duplicate);
            }
            delete.executeBatch();
        }
        plugin.getLogger().warning("Removed " + duplicates.size()
                + " duplicate race hologram entries; keeping one hologram per marked area and race type.");
    }

    private void dropLegacyRaceHologramUniqueIndex(Connection database) throws SQLException {
        if (!indexExists(database, "race_holograms", "ux_race_holograms_server_event_mode")) return;
        boolean mysql = "MySQL".equalsIgnoreCase(database.getMetaData().getDatabaseProductName());
        String sql = mysql
                ? "DROP INDEX ux_race_holograms_server_event_mode ON race_holograms"
                : "DROP INDEX ux_race_holograms_server_event_mode";
        try (Statement statement = database.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    public List<RaceLapTime> listRaceLapTimes(long raceResultId) throws SQLException {
        List<RaceLapTime> lapTimes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT lap, lap_time_ms FROM race_lap_times
                WHERE race_result_id = ? ORDER BY lap
                """)) {
            statement.setLong(1, raceResultId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) lapTimes.add(new RaceLapTime(rows.getInt("lap"), rows.getLong("lap_time_ms")));
            }
        }
        return List.copyOf(lapTimes);
    }

    public List<PlayerRaceLapTime> listPlayerRaceLapTimes(long eventId, String playerUuid) throws SQLException {
        List<PlayerRaceLapTime> lapTimes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT lt.race_result_id, lt.lap, lt.lap_time_ms
                FROM race_lap_times lt JOIN race_results rr ON rr.id = lt.race_result_id
                WHERE rr.event_id = ? AND rr.player_uuid = ? AND rr.end_time IS NOT NULL
                ORDER BY rr.start_time DESC, rr.id DESC, lt.lap
                """)) {
            statement.setLong(1, eventId);
            statement.setString(2, playerUuid);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) lapTimes.add(new PlayerRaceLapTime(rows.getLong("race_result_id"),
                        rows.getInt("lap"), rows.getLong("lap_time_ms")));
            }
        }
        return List.copyOf(lapTimes);
    }

    public List<RaceCheckpointSplit> listRaceCheckpointSplits(long raceResultId) throws SQLException {
        List<RaceCheckpointSplit> splits = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT t.checkpoint_order, c.lap, c.checkpoint_time_ms
                FROM race_checkpoint_times c JOIN event_triggers t ON t.id = c.trigger_id
                WHERE c.race_result_id = ? AND t.trigger_type = 'checkpoint'
                ORDER BY c.lap, t.checkpoint_order
                """)) {
            statement.setLong(1, raceResultId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) splits.add(new RaceCheckpointSplit(rows.getInt("lap"),
                        rows.getInt("checkpoint_order"), rows.getLong("checkpoint_time_ms")));
            }
        }
        return List.copyOf(splits);
    }

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

    /** Returns normal (non-redstone) start triggers in a vertical band at a block column. */
    public List<StartPoint> findStartPoints(String server, String world, int x, int z,
                                            int minY, int maxY) throws SQLException {
        List<StartPoint> points = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.id, e.event_name, e.start_mode, e.laps, t.y,
                       (SELECT COALESCE(MAX(cp.checkpoint_order), 0) FROM event_triggers cp
                        WHERE cp.event_id = e.id AND cp.trigger_type = 'checkpoint') AS max_checkpoint_order
                FROM event_triggers t JOIN events e ON e.id = t.event_id
                WHERE t.trigger_type = 'start' AND t.trigger_mode <> 'REDSTONE_SIGNAL'
                  AND e.start_mode IN ('player', 'signal') AND t.server = ? AND t.world = ?
                  AND t.x = ? AND t.z = ? AND t.y BETWEEN ? AND ?
                """)) {
            statement.setString(1, server);
            statement.setString(2, world);
            statement.setInt(3, x);
            statement.setInt(4, z);
            statement.setInt(5, minY);
            statement.setInt(6, maxY);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) points.add(new StartPoint(rows.getLong("id"), rows.getString("event_name"),
                        rows.getString("start_mode"), rows.getInt("y"), rows.getInt("laps"),
                        rows.getInt("max_checkpoint_order")));
            }
        }
        return points;
    }

    /** Registers the player once and creates a normal in-progress race result. */
    public RaceResult beginRace(long eventId, String playerUuid, String playerName) throws SQLException {
        return beginRace(eventId, playerUuid, playerName, null, System.currentTimeMillis());
    }

    public RaceResult beginSessionRace(long eventId, String playerUuid, String playerName,
                                       RedstoneSession session) throws SQLException {
        if (session.eventId() != eventId) throw new IllegalArgumentException("Session belongs to a different event.");
        return beginRace(eventId, playerUuid, playerName, session.id(), session.startTime());
    }

    private RaceResult beginRace(long eventId, String playerUuid, String playerName,
                                 Long sessionId, long startTime) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            if (sessionId != null) {
                try (PreparedStatement existing = connection.prepareStatement(
                        "SELECT 1 FROM race_session_results WHERE session_id = ? AND player_uuid = ?")) {
                    existing.setLong(1, sessionId);
                    existing.setString(2, playerUuid);
                    try (ResultSet rows = existing.executeQuery()) {
                        if (rows.next()) {
                            connection.rollback();
                            return null;
                        }
                    }
                }
                try (PreparedStatement session = connection.prepareStatement(
                        "SELECT id FROM race_sessions WHERE id = ? AND event_id = ?")) {
                    session.setLong(1, sessionId);
                    session.setLong(2, eventId);
                    try (ResultSet rows = session.executeQuery()) {
                        if (!rows.next()) {
                            connection.rollback();
                            return null;
                        }
                    }
                }
            }
            try (PreparedStatement find = connection.prepareStatement("SELECT 1 FROM players WHERE uuid = ?")) {
                find.setString(1, playerUuid);
                try (ResultSet rows = find.executeQuery()) {
                    if (rows.next()) {
                        try (PreparedStatement update = connection.prepareStatement(
                                "UPDATE players SET last_name = ?, last_seen = ? WHERE uuid = ?")) {
                            update.setString(1, playerName);
                            update.setLong(2, System.currentTimeMillis());
                            update.setString(3, playerUuid);
                            update.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement insert = connection.prepareStatement(
                                "INSERT INTO players (uuid, last_name, last_seen) VALUES (?, ?, ?)")) {
                            insert.setString(1, playerUuid);
                            insert.setString(2, playerName);
                            insert.setLong(3, System.currentTimeMillis());
                            insert.executeUpdate();
                        }
                    }
                }
            }
            long resultId;
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO race_results (event_id, player_uuid, start_time, is_redstone) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                insert.setLong(1, eventId);
                insert.setString(2, playerUuid);
                insert.setLong(3, startTime);
                insert.setBoolean(4, sessionId != null);
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("Could not retrieve the new race result ID.");
                    resultId = keys.getLong(1);
                }
            }
            if (sessionId != null) {
                try (PreparedStatement link = connection.prepareStatement("""
                        INSERT INTO race_session_results (session_id, race_result_id, player_uuid)
                        VALUES (?, ?, ?)
                        """)) {
                    link.setLong(1, sessionId);
                    link.setLong(2, resultId);
                    link.setString(3, playerUuid);
                    link.executeUpdate();
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE race_sessions SET started_count = started_count + 1 WHERE id = ?")) {
                    update.setLong(1, sessionId);
                    if (update.executeUpdate() != 1) throw new SQLException("The redstone session is no longer active.");
                }
            }
            connection.commit();
            return new RaceResult(resultId, startTime, sessionId);
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    public record StartPoint(long eventId, String eventName, String startMode, int triggerY,
                             int laps, int maxCheckpointOrder) { }
    public record RaceResult(long id, long startTime, Long sessionId) { }

    public List<RedstoneStartPoint> findRedstoneStartTriggers(String server, String world, int x, int y, int z)
            throws SQLException {
        List<RedstoneStartPoint> triggers = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.id, e.event_name FROM event_triggers t JOIN events e ON e.id = t.event_id
                WHERE t.trigger_type = 'start' AND t.trigger_mode = 'REDSTONE_SIGNAL'
                  AND e.start_mode = 'signal' AND t.server = ? AND t.world = ?
                  AND t.x = ? AND t.y = ? AND t.z = ?
                """)) {
            statement.setString(1, server);
            statement.setString(2, world);
            statement.setInt(3, x);
            statement.setInt(4, y);
            statement.setInt(5, z);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) triggers.add(new RedstoneStartPoint(rows.getLong("id"), rows.getString("event_name")));
            }
        }
        return triggers;
    }

    public record RedstoneStartPoint(long eventId, String eventName) { }

    public RedstoneSession activateRedstoneSession(long eventId, long startTime) throws SQLException {
        RedstoneSession current = getActiveRedstoneSession(eventId);
        if (current != null) return refreshArmedSession(current, startTime);
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            current = getActiveRedstoneSession(eventId);
            if (current != null) {
                if (current.startedCount() == 0) {
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE race_sessions SET start_time = ? WHERE id = ? AND started_count = 0")) {
                        update.setLong(1, startTime);
                        update.setLong(2, current.id());
                        if (update.executeUpdate() == 1) {
                            current = new RedstoneSession(current.id(), eventId, startTime, 0);
                        } else {
                            current = getActiveRedstoneSession(eventId);
                        }
                    }
                }
                connection.commit();
                return current;
            }
            long id;
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO race_sessions (event_id, start_time) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                insert.setLong(1, eventId);
                insert.setLong(2, startTime);
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("Could not retrieve the new redstone session ID.");
                    id = keys.getLong(1);
                }
            }
            connection.commit();
            return new RedstoneSession(id, eventId, startTime, 0);
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private RedstoneSession refreshArmedSession(RedstoneSession session, long startTime) throws SQLException {
        if (session.startedCount() > 0) return session;
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE race_sessions SET start_time = ? WHERE id = ? AND started_count = 0")) {
            update.setLong(1, startTime);
            update.setLong(2, session.id());
            if (update.executeUpdate() == 1) {
                return new RedstoneSession(session.id(), session.eventId(), startTime, 0);
            }
        }
        return getActiveRedstoneSession(session.eventId());
    }

    public RedstoneSession getActiveRedstoneSession(long eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, start_time, started_count FROM race_sessions WHERE event_id = ? ORDER BY id DESC LIMIT 1")) {
            statement.setLong(1, eventId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? new RedstoneSession(rows.getLong("id"), eventId,
                        rows.getLong("start_time"), rows.getInt("started_count")) : null;
            }
        }
    }

    /** Removes a redstone session if its first driver did not join before its timeout. */
    public void expireUnjoinedRedstoneSession(long sessionId, long expectedStartTime) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM race_sessions
                WHERE id = ? AND start_time = ? AND started_count = 0
                """)) {
            statement.setLong(1, sessionId);
            statement.setLong(2, expectedStartTime);
            statement.executeUpdate();
        }
    }

    public record RedstoneSession(long id, long eventId, long startTime, int startedCount) { }

    public RedstoneSessionSummary finishRedstoneSessionIfIdle(Long sessionId) throws SQLException {
        if (sessionId == null) return null;
        long eventId;
        List<String> podium = new ArrayList<>(List.of("—", "—", "—"));
        List<String> participants = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT rs.event_id, sr.player_uuid, p.last_name, rr.race_time_ms
                FROM race_sessions rs
                JOIN race_session_results sr ON sr.session_id = rs.id
                JOIN race_results rr ON rr.id = sr.race_result_id
                JOIN players p ON p.uuid = sr.player_uuid
                WHERE rs.id = ? AND rs.started_count > 0
                ORDER BY rr.race_time_ms ASC
                """)) {
            statement.setLong(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                eventId = rows.getLong("event_id");
                int place = 0;
                do {
                    participants.add(rows.getString("player_uuid"));
                    if (rows.getObject("race_time_ms") != null && place < podium.size()) {
                        podium.set(place++, rows.getString("last_name"));
                    }
                } while (rows.next());
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM race_sessions
                WHERE id = ? AND started_count > 0 AND NOT EXISTS (
                    SELECT 1 FROM race_session_results sr JOIN race_results rr ON rr.id = sr.race_result_id
                    WHERE sr.session_id = race_sessions.id AND rr.end_time IS NULL
                )
                """)) {
            statement.setLong(1, sessionId);
            if (statement.executeUpdate() == 0 || "—".equals(podium.get(0))) return null;
        }
        return new RedstoneSessionSummary(eventId, List.copyOf(podium), List.copyOf(participants));
    }

    public record RedstoneSessionSummary(long eventId, List<String> podium, List<String> participantUuids) { }

    private void deleteIdleRedstoneSessions() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    DELETE FROM race_sessions
                    WHERE started_count > 0 AND NOT EXISTS (
                        SELECT 1 FROM race_session_results sr JOIN race_results rr ON rr.id = sr.race_result_id
                        WHERE sr.session_id = race_sessions.id AND rr.end_time IS NULL
                    )
                    """);
        }
    }

    public List<CheckpointPoint> findCheckpointTriggers(String server, String world, int x, int z,
                                                         int minY, int maxY) throws SQLException {
        List<CheckpointPoint> points = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT t.id AS trigger_id, t.event_id, e.event_name, t.checkpoint_order, t.y
                FROM event_triggers t JOIN events e ON e.id = t.event_id
                WHERE t.trigger_type = 'checkpoint' AND t.server = ? AND t.world = ?
                  AND t.x = ? AND t.z = ? AND t.y BETWEEN ? AND ?
                """)) {
            statement.setString(1, server);
            statement.setString(2, world);
            statement.setInt(3, x);
            statement.setInt(4, z);
            statement.setInt(5, minY);
            statement.setInt(6, maxY);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) points.add(new CheckpointPoint(rows.getLong("trigger_id"),
                        rows.getLong("event_id"), rows.getString("event_name"),
                        rows.getInt("checkpoint_order"), rows.getInt("y")));
            }
        }
        return points;
    }

    public record CheckpointPoint(long triggerId, long eventId, String eventName,
                                  int checkpointOrder, int triggerY) { }

    public List<EndPoint> findEndTriggers(String server, String world, int x, int z,
                                          int minY, int maxY) throws SQLException {
        List<EndPoint> points = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT t.id AS trigger_id, t.event_id, e.event_name, t.y
                FROM event_triggers t JOIN events e ON e.id = t.event_id
                WHERE t.trigger_type = 'end' AND t.server = ? AND t.world = ?
                  AND t.x = ? AND t.z = ? AND t.y BETWEEN ? AND ?
                """)) {
            statement.setString(1, server);
            statement.setString(2, world);
            statement.setInt(3, x);
            statement.setInt(4, z);
            statement.setInt(5, minY);
            statement.setInt(6, maxY);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) points.add(new EndPoint(rows.getLong("trigger_id"),
                        rows.getLong("event_id"), rows.getString("event_name"), rows.getInt("y")));
            }
        }
        return points;
    }

    public record EndPoint(long triggerId, long eventId, String eventName, int triggerY) { }

    /** Stores a completed lap, and final timing fields when the event has ended. */
    public void recordCompletedLap(long raceResultId, int completedLaps, long lapTimeMillis,
                                   Long endTime, Long raceTimeMillis) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement lap = connection.prepareStatement("""
                    INSERT INTO race_lap_times (race_result_id, lap, lap_time_ms) VALUES (?, ?, ?)
                    """)) {
                lap.setLong(1, raceResultId);
                lap.setInt(2, completedLaps);
                lap.setLong(3, lapTimeMillis);
                lap.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE race_results SET laps_completed = ?, end_time = ?, race_time_ms = ?
                    WHERE id = ? AND end_time IS NULL
                    """)) {
                statement.setInt(1, completedLaps);
                if (endTime == null) statement.setNull(2, java.sql.Types.BIGINT);
                else statement.setLong(2, endTime);
                if (raceTimeMillis == null) statement.setNull(3, java.sql.Types.BIGINT);
                else statement.setLong(3, raceTimeMillis);
                statement.setLong(4, raceResultId);
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("The race result was already completed or no longer exists.");
                }
            }
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    public void recordCheckpoint(long raceResultId, long triggerId, int lap, long elapsedMillis) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO race_checkpoint_times (race_result_id, trigger_id, lap, checkpoint_time_ms)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setLong(1, raceResultId);
            statement.setLong(2, triggerId);
            statement.setInt(3, lap);
            statement.setLong(4, elapsedMillis);
            statement.executeUpdate();
        }
    }

    /** Deletes unfinished race results and their checkpoint data for a player. */
    public int cancelActiveRaces(String playerUuid) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement checkpoints = connection.prepareStatement("""
                    DELETE FROM race_checkpoint_times
                    WHERE race_result_id IN (
                        SELECT id FROM race_results WHERE player_uuid = ? AND end_time IS NULL
                    )
                    """)) {
                checkpoints.setString(1, playerUuid);
                checkpoints.executeUpdate();
            }
            int deleted;
            try (PreparedStatement races = connection.prepareStatement(
                    "DELETE FROM race_results WHERE player_uuid = ? AND end_time IS NULL")) {
                races.setString(1, playerUuid);
                deleted = races.executeUpdate();
            }
            deleteIdleRedstoneSessions();
            connection.commit();
            return deleted;
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

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

    /** Removes every race result and checkpoint time for an event while keeping the event and its triggers. */
    public void resetEventResults(long eventId) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            executeDelete("DELETE FROM race_checkpoint_times WHERE race_result_id IN (SELECT id FROM race_results WHERE event_id = ?)", eventId);
            executeDelete("DELETE FROM race_results WHERE event_id = ?", eventId);
            executeDelete("DELETE FROM race_sessions WHERE event_id = ?", eventId);
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
        return tableStatements(autoIncrement);
    }

    private List<String> tableStatements(String autoIncrement) {
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
                CREATE TABLE IF NOT EXISTS race_sessions (
                    id %s,
                    event_id BIGINT NOT NULL,
                    start_time BIGINT NOT NULL,
                    started_count INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE,
                    CHECK (started_count >= 0)
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
                    is_redstone BOOLEAN NOT NULL DEFAULT FALSE,
                    laps_completed INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE,
                    FOREIGN KEY (player_uuid) REFERENCES players(uuid),
                    CHECK (laps_completed >= 0)
                )
                """.formatted(autoIncrement),
                """
                CREATE TABLE IF NOT EXISTS race_lap_times (
                    id %s,
                    race_result_id BIGINT NOT NULL,
                    lap INTEGER NOT NULL,
                    lap_time_ms BIGINT NOT NULL,
                    FOREIGN KEY (race_result_id) REFERENCES race_results(id) ON DELETE CASCADE,
                    CHECK (lap > 0),
                    CHECK (lap_time_ms >= 0),
                    UNIQUE (race_result_id, lap)
                )
                """.formatted(autoIncrement),
                """
                CREATE TABLE IF NOT EXISTS race_session_results (
                    session_id BIGINT NOT NULL,
                    race_result_id BIGINT NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    PRIMARY KEY (session_id, race_result_id),
                    UNIQUE (session_id, player_uuid),
                    FOREIGN KEY (session_id) REFERENCES race_sessions(id) ON DELETE CASCADE,
                    FOREIGN KEY (race_result_id) REFERENCES race_results(id) ON DELETE CASCADE
                )
                """,
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
                """.formatted(autoIncrement),
                """
                CREATE TABLE IF NOT EXISTS race_holograms (
                    id VARCHAR(64) PRIMARY KEY,
                    event_id BIGINT NOT NULL,
                    redstone BOOLEAN NOT NULL,
                    server VARCHAR(128) NOT NULL,
                    world VARCHAR(128) NOT NULL,
                    low_x INTEGER NOT NULL,
                    low_y INTEGER NOT NULL,
                    low_z INTEGER NOT NULL,
                    high_x INTEGER NOT NULL,
                    high_y INTEGER NOT NULL,
                    high_z INTEGER NOT NULL,
                    center_x DOUBLE NOT NULL,
                    top_y DOUBLE NOT NULL,
                    center_z DOUBLE NOT NULL,
                    yaw REAL NOT NULL DEFAULT 0,
                    FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE
                )
                """
        );
    }

    private void createIndexes() throws SQLException {
        createIndexes(connection);
    }

    private void createIndexes(Connection database) throws SQLException {
        List<IndexDefinition> indexes = List.of(
                new IndexDefinition("event_triggers", "idx_event_triggers_event", "event_id"),
                new IndexDefinition("race_sessions", "idx_race_sessions_event", "event_id"),
                new IndexDefinition("race_results", "idx_race_results_leaderboard", "event_id, race_time_ms"),
                new IndexDefinition("race_results", "idx_race_results_player", "player_uuid"),
                new IndexDefinition("race_lap_times", "idx_race_lap_times_result", "race_result_id"),
                new IndexDefinition("race_checkpoint_times", "idx_checkpoint_times_result", "race_result_id"),
                new IndexDefinition("race_holograms", "idx_race_holograms_server_event", "server, event_id"),
                new IndexDefinition("race_holograms", "ux_race_holograms_placement",
                        "server, event_id, redstone, world, low_x, low_y, low_z, high_x, high_y, high_z")
        );

        for (IndexDefinition index : indexes) {
            if (indexExists(database, index.table(), index.name())) {
                continue;
            }
            try (Statement statement = database.createStatement()) {
                String indexKind = index.name().startsWith("ux_") ? "CREATE UNIQUE INDEX " : "CREATE INDEX ";
                statement.executeUpdate(indexKind + index.name() + " ON "
                        + index.table() + "(" + index.columns() + ")");
            }
        }
    }

    private boolean indexExists(Connection database, String tableName, String indexName) throws SQLException {
        try (ResultSet indexes = database.getMetaData().getIndexInfo(
                database.getCatalog(), null, tableName, false, false)) {
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
