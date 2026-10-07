package de.ethria.trackTimer.race;

import de.ethria.trackTimer.database.DatabaseManager;
import de.ethria.trackTimer.database.DatabaseManager.Event;
import de.ethria.trackTimer.database.DatabaseManager.RaceHologramBoard;
import de.ethria.trackTimer.language.LanguageManager;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.logging.Level;

/** Live standings are computed from memory; only display locations are persisted. */
public final class LiveRaceHologramManager {
    private final JavaPlugin plugin;
    private final DatabaseManager database;
    private final LanguageManager language;
    private final RaceStartListener races;
    private final RaceStatisticsHologramManager renderer;
    private final Map<String, RaceHologramBoard> boards = new LinkedHashMap<>();
    private final Map<Long, Event> events = new HashMap<>();
    private final Map<String, List<String>> lastLines = new HashMap<>();
    private final Map<String, Long> retryAfter = new HashMap<>();
    private BukkitTask task;

    public LiveRaceHologramManager(JavaPlugin plugin, DatabaseManager database, LanguageManager language,
                                  RaceStartListener races, RaceStatisticsHologramManager renderer) {
        this.plugin = plugin; this.database = database; this.language = language;
        this.races = races; this.renderer = renderer;
    }

    public boolean isAvailable() { return renderer.isAvailable(); }

    public void refreshEvent(long eventId) {
        if (!events.containsKey(eventId)) return;
        try {
            Event event = database.getEvent(eventId);
            if (event != null) {
                events.put(eventId, event);
                boards.values().stream().filter(board -> board.eventId() == eventId)
                        .forEach(board -> lastLines.remove(board.id()));
            } else {
                var removed = boards.values().stream().filter(board -> board.eventId() == eventId).toList();
                for (RaceHologramBoard board : removed) {
                    renderer.removeLiveDisplay(board);
                    boards.remove(board.id()); lastLines.remove(board.id()); retryAfter.remove(board.id());
                }
                events.remove(eventId);
            }
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not refresh live hologram event " + eventId, exception);
        }
    }

    public void load() throws SQLException {
        shutdown();
        boards.clear(); events.clear(); lastLines.clear(); retryAfter.clear();
        for (RaceHologramBoard board : database.listLiveHolograms(plugin.getServer().getName())) {
            Event event = database.getEvent(board.eventId());
            if (event == null) continue;
            boards.put(board.id(), board);
            events.put(event.id(), event);
        }
        schedule();
    }

    /** Same area-selection contract as the statistics placement tool. */
    public boolean createBoard(Event event, boolean ignoredMode, Location first, Location second, Location player,
                               LocalDate ignoredDate, LocalTime ignoredTime) throws SQLException {
        RaceHologramBoard board = renderer.livePlacement(event, first, second, player);
        if (board == null) return false;
        boolean exists = boards.values().stream().anyMatch(old -> old.eventId() == board.eventId()
                && old.world().equals(board.world()) && old.lowX() == board.lowX() && old.lowY() == board.lowY()
                && old.lowZ() == board.lowZ() && old.highX() == board.highX() && old.highY() == board.highY()
                && old.highZ() == board.highZ() && old.yaw() == board.yaw());
        if (!exists) {
            database.saveLiveHologram(board);
            boards.put(board.id(), board);
        }
        events.put(event.id(), event);
        if (task == null) schedule();
        return true;
    }

    public List<RaceHologramBoard> boardsAt(Location block) {
        return boards.values().stream().filter(board -> board.world().equals(block.getWorld().getName())
                && block.getBlockX() >= board.lowX() && block.getBlockX() <= board.highX()
                && block.getBlockY() >= board.lowY() && block.getBlockY() <= board.highY()
                && block.getBlockZ() >= board.lowZ() && block.getBlockZ() <= board.highZ()).toList();
    }

    public boolean remove(String id) throws SQLException {
        RaceHologramBoard board = boards.get(id);
        if (board == null || !database.deleteLiveHologram(id, plugin.getServer().getName())) return false;
        boards.remove(id); lastLines.remove(id); retryAfter.remove(id);
        renderer.removeLiveDisplay(board);
        return true;
    }

    private void schedule() {
        if (boards.isEmpty() || !plugin.isEnabled()) return;
        long ticks = Math.max(2, Math.min(1200, plugin.getConfig().getLong("race-statistics.hologram.live.update-interval-ticks", 10)));
        task = Bukkit.getScheduler().runTaskLater(plugin, this::tick, ticks);
    }

    private void tick() {
        task = null;
        try {
            if (!isAvailable()) return;
            long now = System.currentTimeMillis();
            Map<Long, List<String>> linesByEvent = new HashMap<>();
            for (RaceHologramBoard board : boards.values()) {
                if (Bukkit.getWorld(board.world()) == null) continue;
                if (now < retryAfter.getOrDefault(board.id(), 0L)) continue;
                List<String> lines = linesByEvent.computeIfAbsent(board.eventId(), id -> format(events.get(id), now));
                if (!lines.equals(lastLines.get(board.id()))) {
                    if (renderer.renderLive(board, lines)) {
                        lastLines.put(board.id(), lines);
                        retryAfter.remove(board.id());
                    } else retryAfter.put(board.id(), now + 5000);
                }
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not refresh live race holograms.", exception);
        } finally {
            schedule();
        }
    }

    private List<String> format(Event event, long now) {
        List<RaceStartListener.RunningRace> entries = new ArrayList<>(races.liveRaces(event.id()));
        entries.sort(Comparator.<RaceStartListener.RunningRace>comparingInt(race -> race.finishedAt() > 0 ? 0 : 1)
                .thenComparing(Comparator.comparingInt(RaceStartListener.RunningRace::lap).reversed())
                .thenComparing(Comparator.comparingInt(RaceStartListener.RunningRace::checkpointProgress).reversed())
                .thenComparingLong(race -> (race.finishedAt() > 0 ? race.finishedAt() : now) - race.startTime())
                .thenComparing(race -> race.player().getUniqueId()));
        boolean redstone = entries.isEmpty() ? "signal".equals(event.startMode()) : entries.getFirst().sessionId() != null;
        var lines = new ArrayList<String>();
        lines.add(text(redstone ? "live-hologram.race-title" : "live-hologram.free-title", "event", event.name()));
        if (entries.isEmpty()) {
            lines.add(text("live-hologram.idle"));
            return lines;
        }
        boolean heads = plugin.getConfig().getBoolean("race-statistics.hologram.show-player-heads", true)
                && Bukkit.getPluginManager().isPluginEnabled("CMI")
                && !plugin.getConfig().getString("race-statistics.hologram.provider", "auto").startsWith("decent");
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of(text("live-hologram.columns.rank"),
                text("live-hologram.columns.player"), text("live-hologram.columns.lap"), text("live-hologram.columns.time")));
        int rank = 1;
        for (var race : entries) {
            String time = LegacyComponentSerializer.legacySection().serialize(races.formatDuration(
                    (race.finishedAt() > 0 ? race.finishedAt() : now) - race.startTime()));
            String player = (heads ? "<head:" + race.player().getName() + ":false> " : "")
                    + "&f" + race.player().getName();
            rows.add(List.of("&e" + rank++, player, "&f" + race.lap() + "/" + race.laps(), time));
        }
        lines.addAll(HologramTableFormatter.format(rows));
        return lines;
    }

    private String text(String key, Object... values) {
        return LegacyComponentSerializer.legacySection().serialize(language.chatFragment(key, LanguageManager.placeholders(values)));
    }

    public void shutdown() {
        if (task != null) task.cancel();
        task = null;
        boards.values().forEach(renderer::removeLiveDisplay);
    }
}
