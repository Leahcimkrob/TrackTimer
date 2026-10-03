package de.ethria.trackTimer.race;

import de.ethria.trackTimer.database.DatabaseManager;
import de.ethria.trackTimer.database.DatabaseManager.Event;
import de.ethria.trackTimer.database.DatabaseManager.RaceStatisticsEntry;
import de.ethria.trackTimer.language.LanguageManager;
import eu.decentsoftware.holograms.api.DHAPI;
import eu.decentsoftware.holograms.api.DecentHologramsAPI;
import eu.decentsoftware.holograms.api.holograms.Hologram;
import eu.decentsoftware.holograms.display.DisplayBase;
import eu.decentsoftware.holograms.display.DisplaySettings;
import eu.decentsoftware.holograms.display.DisplayService;
import eu.decentsoftware.holograms.display.TextDisplay;
import eu.decentsoftware.holograms.display.attribute.DisplayAttribute;
import eu.decentsoftware.holograms.display.attribute.definition.BillboardAttributeDefinition;
import eu.decentsoftware.holograms.display.attribute.definition.ScaleAttributeDefinition;
import eu.decentsoftware.holograms.display.attribute.value.display.BillboardConstraintsValue;
import eu.decentsoftware.holograms.display.attribute.value.primitives.Vector3fValue;
import eu.decentsoftware.holograms.platform.api.data.DecentLocation;
import eu.decentsoftware.holograms.platform.api.data.display.DisplayBillboardConstraints;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Persists and updates TrackTimer leaderboards made with DecentHolograms. */
public final class RaceStatisticsHologramManager {
    private static final String FILE_NAME = "holograms.yml";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final JavaPlugin plugin;
    private final DatabaseManager database;
    private final LanguageManager language;
    private final RaceStatisticsEvaluator statistics;
    private final File file;
    private YamlConfiguration configuration;
    private Provider provider = Provider.NONE;

    public RaceStatisticsHologramManager(JavaPlugin plugin, DatabaseManager database,
                                         LanguageManager language, RaceStatisticsEvaluator statistics) {
        this.plugin = plugin;
        this.database = database;
        this.language = language;
        this.statistics = statistics;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
    }

    public boolean isAvailable() {
        resolveProvider();
        return provider != Provider.NONE;
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
        if (!file.exists()) {
            try {
                if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
                    plugin.getLogger().warning("Could not create plugin data folder for race holograms.");
                    return;
                }
                if (!file.createNewFile()) return;
            } catch (IOException exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not create holograms.yml.", exception);
                return;
            }
        }
        configuration = YamlConfiguration.loadConfiguration(file);
        if (!isAvailable()) {
            ConfigurationSection savedBoards = configuration.getConfigurationSection("boards");
            if (savedBoards != null && !savedBoards.getKeys(false).isEmpty()) {
                plugin.getLogger().warning("DecentHolograms is not installed; saved race holograms will be dormant.");
            }
            return;
        }
        ConfigurationSection boards = configuration.getConfigurationSection("boards");
        if (boards == null) return;
        for (String key : boards.getKeys(false)) {
            Board board = readBoard(key, boards.getConfigurationSection(key));
            if (board != null) refresh(board);
        }
    }

    public boolean createBoard(Event event, boolean redstone, Location firstCorner,
                               Location secondCorner, Location playerLocation) {
        if (!isAvailable() || configuration == null) return false;
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
            z = planeZ + (towardPlayer < 0 ? -0.65 : 0.65);
            yaw = towardPlayer < 0 ? 180f : 0f;
        } else {
            double planeX = lowX + 0.5;
            double towardPlayer = playerLocation.getX() - planeX;
            x = planeX + (towardPlayer < 0 ? -0.65 : 0.65);
            yaw = towardPlayer < 0 ? 90f : -90f;
        }

        String key = UUID.randomUUID().toString().replace("-", "");
        String hologramName = "tracktimer_" + event.id() + "_" + key;
        Board board = new Board(key, hologramName, event.id(), redstone, world.getName(),
                lowX, lowY, lowZ, highX, highY, highZ, x, highY + 0.5, z, yaw);
        configuration.set("boards." + key, board.serialize());
        save();
        refresh(board);
        return true;
    }

    public void updateEvent(long eventId) {
        if (!isAvailable() || configuration == null) return;
        ConfigurationSection boards = configuration.getConfigurationSection("boards");
        if (boards == null) return;
        for (String key : boards.getKeys(false)) {
            Board board = readBoard(key, boards.getConfigurationSection(key));
            if (board != null && board.eventId() == eventId) refresh(board);
        }
    }

    private void refresh(Board board) {
        if (!isAvailable()) return;
        World world = Bukkit.getWorld(board.world());
        if (world == null) return;
        List<String> lines;
        try {
            var result = statistics.evaluate(board.eventId(), RaceStatisticsEvaluator.Output.HOLOGRAM,
                    null, null, board.redstone());
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
        String displayName = board.hologramName() + "_display";
        Hologram oldHologram = DHAPI.getHologram(board.hologramName());
        if (oldHologram != null) DHAPI.removeHologram(board.hologramName());

        DisplayService displayService = DecentHologramsAPI.get().getDisplayModule().getDisplayService();
        DisplayBase existing = displayService.getDisplay(displayName);
        TextDisplay display;
        boolean created = !(existing instanceof TextDisplay);
        if (existing instanceof TextDisplay textDisplay) {
            display = textDisplay;
            display.setLocation(decentLocation(location));
            display.setLines(lines);
        } else {
            if (existing != null) displayService.deleteDisplay(displayName);
            display = new TextDisplay(displayName, decentLocation(location), new DisplaySettings());
            display.setLines(lines);
        }
        float[] scale = calculateScale(board, lines);
        display.setAttribute(ScaleAttributeDefinition.KEY,
                new DisplayAttribute<>(ScaleAttributeDefinition.KEY,
                        new Vector3fValue(scale[0], scale[1], 1f)));
        display.setAttribute(BillboardAttributeDefinition.KEY,
                new DisplayAttribute<>(BillboardAttributeDefinition.KEY,
                        new BillboardConstraintsValue(DisplayBillboardConstraints.FIXED)));
        if (created) displayService.registerDisplay(display);
        displayService.updateDisplay(display);
        displayService.saveDisplay(display);
    }

    private void updateCmiHologram(Board board, Location location, List<String> lines) {
        try {
            Class<?> cmiClass = Class.forName("com.Zrips.CMI.CMI");
            Object cmi = cmiClass.getMethod("getInstance").invoke(null);
            Object manager = cmiClass.getMethod("getHologramManager").invoke(cmi);
            Method getByName = manager.getClass().getMethod("getByName", String.class);
            Object hologram = getByName.invoke(manager, board.hologramName());
            boolean created = hologram == null;
            Class<?> hologramClass = Class.forName("com.Zrips.CMI.Modules.Holograms.CMIHologram");
            if (created) {
                Constructor<?> constructor = hologramClass.getConstructor(String.class, Location.class);
                hologram = constructor.newInstance(board.hologramName(), location);
            }
            invoke(hologramClass, hologram, "setLocation", new Class<?>[]{Location.class}, location);
            Object pages = invoke(hologramClass, hologram, "getPages", new Class<?>[0]);
            invoke(pages.getClass(), pages, "setLines", new Class<?>[]{List.class}, lines);
            float[] scale = calculateScale(board, lines);
            Object settings = invoke(hologramClass, hologram, "getSettings", new Class<?>[0]);
            Class<?> vectorClass = Class.forName("net.Zrips.CMILib.Container.CMIVector2D");
            Object vector = vectorClass.getConstructor(double.class, double.class)
                    .newInstance((double) scale[0], (double) scale[1]);
            invoke(settings.getClass(), settings, "setScale", new Class<?>[]{vectorClass}, vector);
            Class<?> billboardClass = Class.forName("com.Zrips.CMI.Modules.Display.CMIBillboard");
            Object fixedBillboard = billboardClass.getField("FIXED").get(null);
            invoke(settings.getClass(), settings, "setBillboard", new Class<?>[]{billboardClass}, fixedBillboard);
            invoke(settings.getClass(), settings, "setYaw", new Class<?>[]{double.class}, (double) board.yaw());
            invoke(hologramClass, hologram, created ? "show" : "update", new Class<?>[0]);
            invoke(hologramClass, hologram, "saveToFile", new Class<?>[0]);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("CMI hologram API is unavailable or incompatible.", exception);
        }
    }

    private DecentLocation decentLocation(Location location) {
        return new DecentLocation(location.getWorld().getName(), location.getX(), location.getY(),
                location.getZ(), location.getYaw(), location.getPitch());
    }

    private float[] calculateScale(Board board, List<String> lines) {
        int longestLine = lines.stream()
                .map(line -> ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', line)))
                .filter(java.util.Objects::nonNull)
                .mapToInt(String::length)
                .max().orElse(1);
        double width = board.lowZ() == board.highZ()
                ? board.highX() - board.lowX() + 1.0
                : board.highZ() - board.lowZ() + 1.0;
        double height = board.highY() - board.lowY() + 1.0;
        double naturalWidth = Math.max(0.5, longestLine * 0.12);
        double naturalHeight = Math.max(0.3, lines.size() * 0.3);
        float scaleX = (float) Math.max(0.1, Math.min(1.0, width / naturalWidth));
        float scaleY = (float) Math.max(0.1, Math.min(1.0, height / naturalHeight));
        return new float[]{scaleX, scaleY};
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
            int rank = 1;
            for (RaceStatisticsEntry entry : evaluation.entries()) {
                lines.add(asHologramText(language.chatFragment("race-statistics.normal-entry",
                        LanguageManager.placeholders("rank", rank++, "event", entry.eventName(),
                                "player", entry.playerName(), "race_time", formatDuration(entry.raceTimeMillis()),
                                "laps", entry.lapsCompleted(),
                                "races", evaluation.racesDrivenBy(entry.playerUuid())))));
            }
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
        for (RaceStatisticsEntry entry : evaluation.entries()) {
            long session = Math.floorDiv(entry.startTimeMillis(), 1000);
            if (session != currentSession) {
                Instant start = Instant.ofEpochMilli(entry.startTimeMillis());
                lines.add(asHologramText(language.chatFragment("race-statistics.redstone-chat-title",
                        LanguageManager.placeholders("event", evaluation.event().name(),
                                "date", dateFormat.format(start.atZone(zone)),
                                "time_of_day", timeFormat.format(start.atZone(zone))))));
                currentSession = session;
                rank = 1;
            }
            lines.add(asHologramText(language.chatFragment("race-statistics.redstone-entry",
                    LanguageManager.placeholders("rank", rank++, "event", entry.eventName(),
                            "player", entry.playerName(), "race_time", formatDuration(entry.raceTimeMillis()),
                            "laps", entry.lapsCompleted()))));
        }
        return lines;
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

    private Board readBoard(String key, ConfigurationSection section) {
        if (section == null || !(section.get("event-id") instanceof Number) || !section.isString("world")
                || !section.isString("hologram-name")) return null;
        return new Board(key, section.getString("hologram-name"), section.getLong("event-id"),
                section.getBoolean("redstone"), section.getString("world"),
                section.getInt("low-x"), section.getInt("low-y"), section.getInt("low-z"),
                section.getInt("high-x"), section.getInt("high-y"), section.getInt("high-z"),
                section.getDouble("center-x"), section.getDouble("top-y"), section.getDouble("center-z"),
                (float) section.getDouble("yaw", 0f));
    }

    private void save() {
        if (configuration == null) return;
        try {
            configuration.save(file);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not save holograms.yml.", exception);
        }
    }

    private record Board(String key, String hologramName, long eventId, boolean redstone, String world,
                         int lowX, int lowY, int lowZ, int highX, int highY, int highZ,
                         double centerX, double topY, double centerZ, float yaw) {
        private Map<String, Object> serialize() {
            return Map.ofEntries(
                    Map.entry("hologram-name", hologramName), Map.entry("event-id", eventId),
                    Map.entry("redstone", redstone), Map.entry("world", world),
                    Map.entry("low-x", lowX), Map.entry("low-y", lowY), Map.entry("low-z", lowZ),
                    Map.entry("high-x", highX), Map.entry("high-y", highY), Map.entry("high-z", highZ),
                    Map.entry("center-x", centerX), Map.entry("top-y", topY), Map.entry("center-z", centerZ),
                    Map.entry("yaw", yaw));
        }
    }

    private enum Provider { NONE, CMI, DECENT_HOLOGRAMS }
}
