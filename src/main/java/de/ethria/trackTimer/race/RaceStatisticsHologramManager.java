package de.ethria.trackTimer.race;

import de.ethria.trackTimer.database.DatabaseManager;
import de.ethria.trackTimer.database.DatabaseManager.Event;
import de.ethria.trackTimer.database.DatabaseManager.RaceHologramBoard;
import de.ethria.trackTimer.database.DatabaseManager.RaceStatisticsEntry;
import de.ethria.trackTimer.language.LanguageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.logging.Level;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Stores TrackTimer leaderboard locations in its database and renders them through an installed provider. */
public final class RaceStatisticsHologramManager {
    private static final String LEGACY_FILE_NAME = "holograms.yml";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final JavaPlugin plugin;
    private final DatabaseManager database;
    private final LanguageManager language;
    private final RaceStatisticsEvaluator statistics;
    private static final double WALL_GAP = 0.05;
    // Stored top_y is the visible upper edge, a quarter block below the block's top.
    private static final double VERTICAL_OFFSET = 1.0 - 0.25;
    private final CmiHologramTopAligner cmiTopAligner;
    private Provider provider = Provider.NONE;
    private final Set<String> activeCmiBoards = new HashSet<>();

    public RaceStatisticsHologramManager(JavaPlugin plugin, DatabaseManager database,
                                         LanguageManager language, RaceStatisticsEvaluator statistics) {
        this.plugin = plugin;
        this.database = database;
        this.language = language;
        this.statistics = statistics;
        this.cmiTopAligner = new CmiHologramTopAligner(plugin);
    }

    public boolean isAvailable() {
        resolveProvider();
        return provider != Provider.NONE;
    }

    public void logProviderStatus() {
        resolveProvider();
        String message = switch (provider) {
            case CMI -> "Hologram-Provider erkannt: CMI.";
            case DECENT_HOLOGRAMS -> "Hologram-Provider erkannt: DecentHolograms.";
            case NONE -> "Kein Hologram-Provider erkannt. Die Hologramm-Funktion ist deaktiviert.";
        };
        plugin.getLogger().info(message);
    }

    private void resolveProvider() {
        String requested = plugin.getConfig().getString("race-statistics.hologram.provider", "auto")
                .trim().toLowerCase(Locale.ROOT);
        boolean cmi = Bukkit.getPluginManager().isPluginEnabled("CMI");
        boolean decent = Bukkit.getPluginManager().isPluginEnabled("DecentHolograms");
        provider = switch (requested) {
            case "cmi" -> cmi ? Provider.CMI : Provider.NONE;
            case "decentholograms", "decent-holograms" -> decent ? Provider.DECENT_HOLOGRAMS : Provider.NONE;
            default -> cmi ? Provider.CMI : decent ? Provider.DECENT_HOLOGRAMS : Provider.NONE;
        };
        if (!requested.equals("auto") && provider == Provider.NONE) {
            plugin.getLogger().warning("Configured hologram provider '" + requested
                    + "' is not installed or enabled; race holograms are disabled.");
        }
    }

    public void load() {
        try {
            migrateLegacyFile();
            List<RaceHologramBoard> boards = new ArrayList<>();
            for (RaceHologramBoard stored : database.listRaceHologramBoards(plugin.getServer().getName())) {
                RaceHologramBoard adjusted = adjustPlacement(stored);
                if (Double.compare(stored.centerX(), adjusted.centerX()) != 0
                        || Double.compare(stored.topY(), adjusted.topY()) != 0
                        || Double.compare(stored.centerZ(), adjusted.centerZ()) != 0) {
                    database.updateRaceHologramPosition(adjusted.id(), adjusted.server(), adjusted.centerX(),
                            adjusted.topY(), adjusted.centerZ());
                }
                boards.add(adjusted);
            }
            if (!isAvailable()) {
                plugin.getLogger().info("Race hologram definitions remain in the TrackTimer database; no provider is enabled.");
                return;
            }
            removeDuplicateProviderHolograms(database.duplicateRaceHologramBoards());
            for (RaceHologramBoard board : boards) {
                refresh(fromDatabase(board));
            }
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not load race holograms from the database.", exception);
        }
    }

    /** Recreates existing displays after removing them from the previously selected provider. */
    public void reload() throws SQLException {
        List<RaceHologramBoard> boards = database.listRaceHologramBoards(plugin.getServer().getName());
        for (RaceHologramBoard board : boards) removeProviderHologram(fromDatabase(board));
        resolveProvider();
        if (provider == Provider.NONE) return;
        for (RaceHologramBoard board : boards) {
            RaceHologramBoard adjusted = adjustPlacement(board);
            database.updateRaceHologramPosition(adjusted.id(), adjusted.server(), adjusted.centerX(),
                    adjusted.topY(), adjusted.centerZ());
            refresh(fromDatabase(adjusted));
        }
    }

    public boolean createBoard(Event event, boolean redstone, Location firstCorner,
                               Location secondCorner, Location playerLocation) throws SQLException {
        return createBoard(event, redstone, firstCorner, secondCorner, playerLocation, null, null);
    }

    public boolean createBoard(Event event, boolean redstone, Location firstCorner,
                               Location secondCorner, Location playerLocation,
                               LocalDate filterDate, LocalTime filterTime) throws SQLException {
        if (!isAvailable()) return false;
        if (!firstCorner.getWorld().equals(secondCorner.getWorld())) return false;
        int x1 = firstCorner.getBlockX();
        int y1 = firstCorner.getBlockY();
        int z1 = firstCorner.getBlockZ();
        int x2 = secondCorner.getBlockX();
        int y2 = secondCorner.getBlockY();
        int z2 = secondCorner.getBlockZ();
        if (x1 != x2 && z1 != z2) return false;

        World world = firstCorner.getWorld();
        int lowX = Math.min(x1, x2);
        int highX = Math.max(x1, x2);
        int lowY = Math.min(y1, y2);
        int highY = Math.max(y1, y2);
        int lowZ = Math.min(z1, z2);
        int highZ = Math.max(z1, z2);
        boolean xPlane = lowZ == highZ;
        double x = (lowX + highX + 1) / 2.0;
        double z = (lowZ + highZ + 1) / 2.0;
        float yaw;
        if (xPlane) {
            double planeZ = lowZ + 0.5;
            double towardPlayer = playerLocation.getZ() - planeZ;
            z = planeZ + (towardPlayer < 0 ? -1 : 1) * (0.5 + WALL_GAP);
            yaw = towardPlayer < 0 ? 180f : 0f;
        } else {
            double planeX = lowX + 0.5;
            double towardPlayer = playerLocation.getX() - planeX;
            x = planeX + (towardPlayer < 0 ? -1 : 1) * (0.5 + WALL_GAP);
            yaw = towardPlayer < 0 ? 90f : -90f;
        }

        String key = UUID.randomUUID().toString();
        String hologramName = "tracktimer_" + event.id() + "_" + key.replace("-", "");
        Board board = new Board(key, hologramName, event.id(), redstone, plugin.getServer().getName(), world.getName(),
                lowX, lowY, lowZ, highX, highY, highZ, x, highY + VERTICAL_OFFSET, z, yaw,
                filterDate == null ? null : filterDate.toString(), filterTime == null ? null : filterTime.toString());
        RaceHologramBoard saved = database.saveRaceHologramBoard(toDatabase(board, plugin.getServer().getName()));
        refresh(fromDatabase(saved));
        return true;
    }

    private RaceHologramBoard adjustPlacement(RaceHologramBoard board) {
        double centerX;
        double centerZ;
        if (board.lowZ() == board.highZ()) {
            centerX = (board.lowX() + board.highX() + 1) / 2.0;
            double planeZ = board.lowZ() + 0.5;
            double direction = board.yaw() == 180f ? -1 : 1;
            centerZ = planeZ + direction * (0.5 + WALL_GAP);
        } else {
            double planeX = board.lowX() + 0.5;
            double direction = board.yaw() == 90f ? -1 : 1;
            centerX = planeX + direction * (0.5 + WALL_GAP);
            centerZ = (board.lowZ() + board.highZ() + 1) / 2.0;
        }
        return new RaceHologramBoard(board.id(), board.eventId(), board.redstone(), board.server(), board.world(),
                board.lowX(), board.lowY(), board.lowZ(), board.highX(), board.highY(), board.highZ(),
                centerX, board.highY() + VERTICAL_OFFSET, centerZ, board.yaw(), board.filterDate(), board.filterTime());
    }

    private void removeDuplicateProviderHolograms(List<RaceHologramBoard> duplicates) {
        for (RaceHologramBoard duplicate : duplicates) {
            removeProviderHologram(fromDatabase(duplicate));
        }
    }

    public int deleteHologramAt(Location clickedBlock, Location playerLocation) throws SQLException {
        if (!isAvailable()) return 0;
        List<RaceHologramBoard> matches = database.listRaceHologramBoardsAt(plugin.getServer().getName(),
                clickedBlock.getWorld().getName(), clickedBlock.getBlockX(), clickedBlock.getBlockY(),
                clickedBlock.getBlockZ());
        RaceHologramBoard selected = matches.stream()
                .filter(board -> isOnSameSideAsPlayer(board, playerLocation))
                .min(java.util.Comparator.comparingDouble(board -> distanceSquaredToPlayer(board, playerLocation)))
                .orElse(null);
        if (selected == null || !database.deleteRaceHologramBoard(selected.id(), plugin.getServer().getName())) {
            return 0;
        }
        removeProviderHologram(fromDatabase(selected));
        return 1;
    }

    private boolean isOnSameSideAsPlayer(RaceHologramBoard board, Location playerLocation) {
        boolean xPlane = board.lowZ() == board.highZ();
        double planeCoordinate = xPlane ? board.lowZ() + 0.5 : board.lowX() + 0.5;
        double playerCoordinate = xPlane ? playerLocation.getZ() : playerLocation.getX();
        double hologramCoordinate = xPlane ? board.centerZ() : board.centerX();
        return Math.signum(playerCoordinate - planeCoordinate) == Math.signum(hologramCoordinate - planeCoordinate);
    }

    private double distanceSquaredToPlayer(RaceHologramBoard board, Location playerLocation) {
        double dx = playerLocation.getX() - board.centerX();
        double dy = playerLocation.getY() - board.topY();
        double dz = playerLocation.getZ() - board.centerZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private void removeProviderHologram(Board board) {
        String hologramName = board.hologramName();
        cmiTopAligner.remove(hologramName);
        try {
            if (provider == Provider.CMI) {
                Class<?> cmiClass = Class.forName("com.Zrips.CMI.CMI");
                Object cmi = cmiClass.getMethod("getInstance").invoke(null);
                Object manager = cmiClass.getMethod("getHologramManager").invoke(cmi);
                Object hologram = manager.getClass().getMethod("getByName", String.class)
                        .invoke(manager, hologramName);
                if (hologram != null) invoke(hologram.getClass(), hologram, "delete", new Class<?>[0]);
                activeCmiBoards.remove(board.key());
            } else if (provider == Provider.DECENT_HOLOGRAMS) {
                Class<?> renderer = Class.forName("de.ethria.trackTimer.race.DecentHologramRenderer");
                renderer.getMethod("remove", String.class).invoke(null, hologramName);
            }
        } catch (ReflectiveOperationException | LinkageError exception) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not remove rendered hologram '" + hologramName + "'.", exception);
        }
    }

    private void removeProviderHologram(RaceHologramBoard board) {
        removeProviderHologram(fromDatabase(board));
    }

    public void updateEvent(long eventId) {
        if (!isAvailable()) return;
        try {
            for (RaceHologramBoard board : database.listRaceHologramBoards(plugin.getServer().getName())) {
                if (board.eventId() == eventId) refresh(fromDatabase(board));
            }
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not reload race hologram rows after race completion.", exception);
        }
    }

    private void refresh(Board board) {
        if (!isAvailable()) return;
        World world = Bukkit.getWorld(board.world());
        if (world == null) return;
        List<String> lines;
        try {
            var result = statistics.evaluate(board.eventId(), RaceStatisticsEvaluator.Output.HOLOGRAM,
                    board.filterDate() == null ? null : LocalDate.parse(board.filterDate()),
                    board.filterTime() == null ? null : LocalTime.parse(board.filterTime()), board.redstone());
            if (result.isEmpty()) return;
            lines = formatLines(result.get());
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Could not update race statistics hologram '"
                    + board.hologramName() + "'.", exception);
            return;
        }

        try {
            Location location = new Location(world, board.centerX(), board.topY(), board.centerZ(), board.yaw(), 0f);
            if (provider == Provider.CMI) {
                updateCmiHologram(board, location, lines);
            } else if (provider == Provider.DECENT_HOLOGRAMS) {
                updateDecentHologram(board, location, lines);
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Hologram provider " + provider + " could not update board '"
                    + board.hologramName() + "'.", exception);
        }
    }

    private void updateDecentHologram(Board board, Location location, List<String> lines) {
        float[] scale = calculateScale(board, lines);
        // Decent's native TextDisplay location is its lower edge; reserve the
        // entire scaled text height so its upper edge remains at top_y.
        Location displayLocation = location.clone().subtract(0, lines.size() * 0.25 * scale[1], 0);
        int maximumLineWidth = measureMaximumLineWidthPixels(lines);
        try {
            Class<?> renderer = Class.forName("de.ethria.trackTimer.race.DecentHologramRenderer");
            renderer.getMethod("render", String.class, Location.class, List.class,
                            float.class, float.class, int.class)
                    .invoke(null, board.hologramName(), displayLocation, lines, scale[0], scale[1], maximumLineWidth);
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException("DecentHolograms display API is unavailable or incompatible.", exception);
        }
    }

    private void updateCmiHologram(Board board, Location location, List<String> lines) {
        try {
            Class<?> cmiClass = Class.forName("com.Zrips.CMI.CMI");
            Object cmi = cmiClass.getMethod("getInstance").invoke(null);
            Object manager = cmiClass.getMethod("getHologramManager").invoke(cmi);
            Method getByName = manager.getClass().getMethod("getByName", String.class);
            Object hologram = getByName.invoke(manager, board.hologramName());
            boolean created = !activeCmiBoards.contains(board.key()) || hologram == null;
            Class<?> hologramClass = Class.forName("com.Zrips.CMI.Modules.Holograms.CMIHologram");
            if (created) {
                if (hologram != null) invoke(hologramClass, hologram, "delete", new Class<?>[0]);
                Constructor<?> constructor = hologramClass.getConstructor(String.class, Location.class);
                hologram = constructor.newInstance(board.hologramName(), location);
            }
            invoke(hologramClass, hologram, "setLocation", new Class<?>[]{Location.class}, location);
            Object pages = invoke(hologramClass, hologram, "getPages", new Class<?>[0]);
            invoke(pages.getClass(), pages, "setLines", new Class<?>[]{List.class}, lines);
            Object textSettings = invoke(hologramClass, hologram, "getTextSettings", new Class<?>[0]);
            invoke(textSettings.getClass(), textSettings, "setLineWidth", new Class<?>[]{int.class},
                    Math.max(1, measureMaximumLineWidthPixels(lines)));
            float[] scale = calculateScale(board, lines);
            Object settings = invoke(hologramClass, hologram, "getSettings", new Class<?>[0]);
            invoke(settings.getClass(), settings, "setSaveToFile", new Class<?>[]{boolean.class}, false);
            Class<?> vectorClass = Class.forName("net.Zrips.CMILib.Container.CMIVector2D");
            Object vector = vectorClass.getConstructor(double.class, double.class)
                    .newInstance((double) scale[0], (double) scale[1]);
            invoke(settings.getClass(), settings, "setScale", new Class<?>[]{vectorClass}, vector);
            Class<?> billboardClass = Class.forName("com.Zrips.CMI.Modules.Display.CMIBillboard");
            Object fixedBillboard = billboardClass.getField("FIXED").get(null);
            invoke(settings.getClass(), settings, "setBillboard", new Class<?>[]{billboardClass}, fixedBillboard);
            invoke(settings.getClass(), settings, "setYaw", new Class<?>[]{double.class}, (double) board.yaw());
            invoke(hologramClass, hologram, created ? "show" : "update", new Class<?>[0]);
            activeCmiBoards.add(board.key());
            cmiTopAligner.align(board.hologramName(), hologram, location);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("CMI hologram API is unavailable or incompatible.", exception);
        }
    }

    private float[] calculateScale(Board board, List<String> lines) {
        int maximumLineWidth = measureMaximumLineWidthPixels(lines);
        double width = board.lowZ() == board.highZ()
                ? board.highX() - board.lowX() + 1.0
                : board.highZ() - board.lowZ() + 1.0;
        double height = board.highY() - board.lowY() + 1.0;
        // A Minecraft text display renders each font pixel at 1/40 block at scale 1.
        // Measure glyph pixels with Bukkit's actual default Minecraft font, then use
        // one uniform factor so the displayed text keeps its proportions.
        double naturalWidth = Math.max(0.025, maximumLineWidth * 0.025);
        double naturalHeight = Math.max(0.25, lines.size() * 0.25);
        double widthScale = width * 0.9 / naturalWidth;
        double heightScale = height * 0.9 / naturalHeight;
        float scale = (float) Math.max(0.01, Math.min(16.0, Math.min(widthScale, heightScale)));
        return new float[]{scale, scale};
    }

    private int measureMaximumLineWidthPixels(List<String> lines) {
        return lines.stream().mapToInt(this::measureLineWidthPixels).max().orElse(1);
    }

    private int measureLineWidthPixels(String line) {
        return Math.max(1, HologramTableFormatter.width(line));
    }

    private Object invoke(Class<?> type, Object receiver, String methodName, Class<?>[] parameterTypes,
                          Object... arguments) throws ReflectiveOperationException {
        Method method = type.getMethod(methodName, parameterTypes);
        return method.invoke(receiver, arguments);
    }

    private List<String> formatLines(RaceStatisticsEvaluator.Evaluation evaluation) {
        List<String> lines = new ArrayList<>();
        ZoneId zone = ZoneId.systemDefault();
        Locale locale = Locale.forLanguageTag(language.getLocale());
        DateTimeFormatter dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale);
        DateTimeFormatter timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(locale);
        if (!evaluation.redstone()) {
            lines.add(asHologramText(language.chatFragment("race-statistics.chat-title",
                    LanguageManager.placeholders("event", evaluation.event().name()))));
            if (evaluation.entries().isEmpty()) {
                lines.add(asHologramText(language.chatFragment("race-statistics.chat-empty")));
                return lines;
            }
            List<List<String>> rows = tableHeader(false);
            int rank = 1;
            for (RaceStatisticsEntry entry : evaluation.entries()) {
                rows.add(List.of("&e" + rank++ + ".", playerCell(entry),
                        "&f" + formatDuration(entry.raceTimeMillis()), "&f" + entry.lapsCompleted(),
                        "&f" + evaluation.racesDrivenBy(entry.playerUuid())));
            }
            lines.addAll(HologramTableFormatter.format(rows));
            return lines;
        }

        if (evaluation.entries().isEmpty()) {
            lines.add(asHologramText(language.chatFragment("race-statistics.chat-title",
                    LanguageManager.placeholders("event", evaluation.event().name()))));
            lines.add(asHologramText(language.chatFragment("race-statistics.chat-empty")));
            return lines;
        }

        long currentSession = Long.MIN_VALUE;
        int rank = 1;
        List<List<String>> rows = tableHeader(true);
        List<List<String>> sizingRows = tableHeader(true);
        int sizingRank = 1;
        for (RaceStatisticsEntry entry : evaluation.entries()) {
            sizingRows.add(List.of("&e" + sizingRank++ + ".", playerCell(entry),
                    "&f" + formatDuration(entry.raceTimeMillis()), "&f" + entry.lapsCompleted()));
        }
        for (RaceStatisticsEntry entry : evaluation.entries()) {
            long session = Math.floorDiv(entry.startTimeMillis(), 1000);
            if (session != currentSession) {
                if (currentSession != Long.MIN_VALUE) {
                    lines.addAll(HologramTableFormatter.format(rows, sizingRows));
                    rows = tableHeader(true);
                }
                Instant start = Instant.ofEpochMilli(entry.startTimeMillis());
                lines.add(asHologramText(language.chatFragment("race-statistics.redstone-chat-title",
                        LanguageManager.placeholders("event", evaluation.event().name(),
                                "date", dateFormat.format(start.atZone(zone)),
                                "time_of_day", timeFormat.format(start.atZone(zone))))));
                currentSession = session;
                rank = 1;
            }
            rows.add(List.of("&e" + rank++ + ".", playerCell(entry),
                    "&f" + formatDuration(entry.raceTimeMillis()), "&f" + entry.lapsCompleted()));
        }
        lines.addAll(HologramTableFormatter.format(rows, sizingRows));
        return lines;
    }

    private List<List<String>> tableHeader(boolean redstone) {
        List<String> header = new ArrayList<>();
        for (String column : List.of("rank", "player", "time", "laps")) {
            String label = asHologramText(language.chatFragment("race-statistics.hologram-columns." + column));
            if (column.equals("player") && showPlayerHeads()) label = "   " + label;
            header.add(label);
        }
        if (!redstone) header.add(asHologramText(language.chatFragment("race-statistics.hologram-columns.races")));
        List<List<String>> rows = new ArrayList<>();
        rows.add(header);
        return rows;
    }

    private boolean showPlayerHeads() {
        return provider == Provider.CMI
                && plugin.getConfig().getBoolean("race-statistics.hologram.show-player-heads", true);
    }

    private String playerCell(RaceStatisticsEntry entry) {
        String head = showPlayerHeads() ? "<head:" + entry.playerName() + ":false> " : "";
        return head + "&f" + entry.playerName();
    }

    private String formatDuration(long millis) {
        long time = Math.max(0, millis);
        Component component = language.chatFragment("race-statistics.duration-format",
                LanguageManager.placeholders(
                        "hours", String.format(Locale.ROOT, "%02d", time / 3_600_000),
                        "minutes", String.format(Locale.ROOT, "%02d", time / 60_000 % 60),
                        "seconds", String.format(Locale.ROOT, "%02d", time / 1_000 % 60),
                        "centiseconds", String.format(Locale.ROOT, "%02d", time / 10 % 100)));
        return asHologramText(component);
    }

    private String asHologramText(Component component) {
        return LEGACY.serialize(component).replace('§', '&');
    }

    private void migrateLegacyFile() throws SQLException {
        File legacyFile = new File(plugin.getDataFolder(), LEGACY_FILE_NAME);
        if (!legacyFile.isFile()) return;

        YamlConfiguration legacy = YamlConfiguration.loadConfiguration(legacyFile);
        ConfigurationSection boards = legacy.getConfigurationSection("boards");
        if (boards == null) {
            if (legacyFile.delete()) plugin.getLogger().info("Removed empty legacy race hologram file.");
            return;
        }

        String server = plugin.getServer().getName();
        Set<String> existingIds = new HashSet<>();
        database.listRaceHologramBoards(server).forEach(board -> existingIds.add(board.id()));
        boolean complete = true;
        for (String key : boards.getKeys(false)) {
            Board board = readLegacyBoard(key, boards.getConfigurationSection(key), server);
            if (board == null) {
                complete = false;
                plugin.getLogger().warning("Could not migrate legacy race hologram '" + key + "'; keeping the old file.");
                continue;
            }
            if (existingIds.add(board.key())) database.saveRaceHologramBoard(toDatabase(board, server));
        }
        if (complete && legacyFile.delete()) {
            plugin.getLogger().info("Migrated race hologram definitions into the TrackTimer database.");
        }
    }

    private Board readLegacyBoard(String key, ConfigurationSection section, String server) {
        if (section == null || !(section.get("event-id") instanceof Number) || !section.isString("world")
                || !section.isString("hologram-name")) return null;
        String id = key.replaceAll("[^A-Za-z0-9_-]", "");
        if (id.isEmpty()) return null;
        String hologramName = "tracktimer_" + section.getLong("event-id") + "_" + id;
        return new Board(id, hologramName, section.getLong("event-id"), section.getBoolean("redstone"),
                server, section.getString("world"),
                section.getInt("low-x"), section.getInt("low-y"), section.getInt("low-z"),
                section.getInt("high-x"), section.getInt("high-y"), section.getInt("high-z"),
                section.getDouble("center-x"), section.getDouble("top-y"), section.getDouble("center-z"),
                (float) section.getDouble("yaw", 0f), null, null);
    }

    private Board fromDatabase(RaceHologramBoard board) {
        String hologramName = "tracktimer_" + board.eventId() + "_" + board.id().replace("-", "");
        return new Board(board.id(), hologramName, board.eventId(), board.redstone(), board.server(), board.world(),
                board.lowX(), board.lowY(), board.lowZ(), board.highX(), board.highY(), board.highZ(),
                board.centerX(), board.topY(), board.centerZ(), board.yaw(), board.filterDate(), board.filterTime());
    }

    private RaceHologramBoard toDatabase(Board board, String server) {
        return new RaceHologramBoard(board.key(), board.eventId(), board.redstone(), server, board.world(),
                board.lowX(), board.lowY(), board.lowZ(), board.highX(), board.highY(), board.highZ(),
                board.centerX(), board.topY(), board.centerZ(), board.yaw(), board.filterDate(), board.filterTime());
    }

    private record Board(String key, String hologramName, long eventId, boolean redstone, String server, String world,
                         int lowX, int lowY, int lowZ, int highX, int highY, int highZ,
                         double centerX, double topY, double centerZ, float yaw,
                         String filterDate, String filterTime) { }

    private enum Provider { NONE, CMI, DECENT_HOLOGRAMS }
}
