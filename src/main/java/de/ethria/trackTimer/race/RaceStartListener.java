package de.ethria.trackTimer.race;

import de.ethria.trackTimer.database.DatabaseManager;
import de.ethria.trackTimer.database.DatabaseManager.RaceResult;
import de.ethria.trackTimer.database.DatabaseManager.RedstoneSession;
import de.ethria.trackTimer.database.DatabaseManager.StartPoint;
import de.ethria.trackTimer.language.LanguageManager;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.bossbar.BossBar.Color;
import net.kyori.adventure.bossbar.BossBar.Overlay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import org.bukkit.Location;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** Detects normal player start blocks and displays the elapsed race time. */
public final class RaceStartListener implements Listener {
    private final JavaPlugin plugin;
    private final DatabaseManager database;
    private final LanguageManager language;
    private final Map<UUID, Map<Long, RunningRace>> running = new HashMap<>();
    private final Map<Long, Map<UUID, RunningRace>> liveFinishers = new HashMap<>();
    private final Map<Long, Long> liveEndedAt = new HashMap<>();

    public RaceStartListener(JavaPlugin plugin, DatabaseManager database, LanguageManager language) {
        this.plugin = plugin;
        this.database = database;
        this.language = language;
    }

    public void onRacePosition(Player player, Location position) {
        checkStartAt(player, position);
    }

    private void checkStartAt(Player player, Location position) {
        double tolerance = TriggerPositionMatcher.heightTolerance(plugin);
        int minY = TriggerPositionMatcher.minY(position, tolerance);
        int maxY = TriggerPositionMatcher.maxY(position, tolerance);
        try {
            for (StartPoint point : database.findStartPoints(plugin.getServer().getName(),
                    position.getWorld().getName(), position.getBlockX(), position.getBlockZ(), minY, maxY)) {
                if (!TriggerPositionMatcher.isWithinHeight(position, point.triggerY(), tolerance)
                        || isRunning(player, point.eventId())) continue;
                RaceResult result;
                if ("signal".equals(point.startMode())) {
                    RedstoneSession session = database.getActiveRedstoneSession(point.eventId());
                    if (session == null) continue;
                    result = database.beginSessionRace(point.eventId(), player.getUniqueId().toString(),
                            player.getName(), session);
                    if (result == null) continue;
                } else {
                    result = database.beginRace(point.eventId(), player.getUniqueId().toString(), player.getName());
                }
                beginDisplay(player, point, result);
            }
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not start race for " + player.getName(), exception);
        }
    }

    private boolean isRunning(Player player, long eventId) {
        return running.getOrDefault(player.getUniqueId(), Map.of()).containsKey(eventId);
    }

    private void beginDisplay(Player player, StartPoint point, RaceResult result) {
        long eventId = point.eventId();
        resetLiveStandings(eventId);
        Map<UUID, RunningRace> previousFinishers = liveFinishers.get(eventId);
        if (previousFinishers != null) previousFinishers.remove(player.getUniqueId());
        String eventName = point.eventName();
        long startTime = result.startTime();
        if (result.sessionId() == null) {
            player.showTitle(Title.title(language.chatFragment("race.start-title"), Component.empty()));
        }
        BossBar bar = BossBar.bossBar(language.chatFragment("race.bossbar.text", LanguageManager.placeholders(
                "event", eventName, "time", format(startTime, startTime))), 1.0f, Color.GREEN, Overlay.PROGRESS);
        player.showBossBar(bar);
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                stopDisplay(player.getUniqueId(), eventId);
                return;
            }
            bar.name(language.chatFragment("race.bossbar.text", LanguageManager.placeholders(
                    "event", eventName, "time", format(startTime, System.currentTimeMillis()))));
        }, 0L, 2L);
        running.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>())
                .put(eventId, new RunningRace(player, eventId, eventName, result.id(), startTime,
                        result.sessionId(), point.laps(), point.maxCheckpointOrder(), bar, task));
    }

    public List<RunningRace> activeRaces(Player player) {
        return List.copyOf(running.getOrDefault(player.getUniqueId(), Map.of()).values());
    }

    public RunningRace activeRace(Player player, long eventId) {
        return running.getOrDefault(player.getUniqueId(), Map.of()).get(eventId);
    }

    public void finishRace(Player player, long eventId) {
        finishRace(player, eventId, System.currentTimeMillis());
    }

    public void finishRace(Player player, long eventId, long finishedAt) {
        RunningRace race = activeRace(player, eventId);
        if (race != null) {
            race.finishedAt = finishedAt;
            liveFinishers.computeIfAbsent(eventId, ignored -> new HashMap<>()).put(player.getUniqueId(), race);
        }
        stopDisplay(player.getUniqueId(), eventId);
    }

    public void resetLiveStandings(long eventId) {
        boolean active = running.values().stream().anyMatch(entries -> entries.containsKey(eventId));
        if (!active) {
            liveFinishers.remove(eventId);
            liveEndedAt.remove(eventId);
        }
    }

    /** Finished drivers remain visible until expiry or the next start. No database reads. */
    public List<RunningRace> liveRaces(long eventId) {
        var active = running.values().stream().map(races -> races.get(eventId)).filter(java.util.Objects::nonNull).toList();
        Map<UUID, RunningRace> finished = liveFinishers.get(eventId);
        if (finished != null && active.isEmpty()) {
            long now = System.currentTimeMillis();
            long endedAt = liveEndedAt.computeIfAbsent(eventId, ignored -> now);
            double minutes = Math.max(0, Math.min(1440,
                    plugin.getConfig().getDouble("race-statistics.hologram.live.result-display-minutes", 10)));
            if (now - endedAt >= minutes * 60000) {
                liveFinishers.remove(eventId);
                liveEndedAt.remove(eventId);
                finished = null;
            }
        }
        var result = new java.util.ArrayList<>(active);
        if (finished != null) result.addAll(finished.values());
        return List.copyOf(result);
    }

    public Component formatDuration(long elapsedMillis) {
        elapsedMillis = Math.max(0, elapsedMillis);
        long hours = elapsedMillis / 3_600_000;
        long minutes = elapsedMillis / 60_000 % 60;
        long seconds = elapsedMillis / 1_000 % 60;
        long centiseconds = elapsedMillis / 10 % 100;
        return language.chatFragment("race.bossbar.time-format", LanguageManager.placeholders(
                "hours", String.format(java.util.Locale.ROOT, "%02d", hours),
                "minutes", String.format(java.util.Locale.ROOT, "%02d", minutes),
                "seconds", String.format(java.util.Locale.ROOT, "%02d", seconds),
                "ms", String.format(java.util.Locale.ROOT, "%02d", centiseconds)));
    }

    private Component format(long startTime, long now) {
        return formatDuration(now - startTime);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        try {
            cancelPlayerRaces(event.getPlayer());
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not remove unfinished race data for "
                    + event.getPlayer().getName() + " on disconnect.", exception);
            stopDisplays(event.getPlayer().getUniqueId());
        }
    }

    public int cancelPlayerRaces(Player player) throws SQLException {
        int deleted = database.cancelActiveRaces(player.getUniqueId().toString());
        stopDisplays(player.getUniqueId());
        return deleted;
    }

    private void stopDisplays(UUID playerId) {
        Map<Long, RunningRace> races = running.remove(playerId);
        if (races != null) {
            races.values().forEach(RunningRace::stop);
            races.keySet().forEach(this::liveRaces);
        }
    }

    public void shutdown() {
        running.values().forEach(races -> races.values().forEach(RunningRace::stop));
        running.clear();
        liveFinishers.clear();
        liveEndedAt.clear();
    }

    private void stopDisplay(UUID playerId, long eventId) {
        Map<Long, RunningRace> races = running.get(playerId);
        if (races == null) return;
        RunningRace race = races.remove(eventId);
        if (race != null) race.stop();
        if (races.isEmpty()) running.remove(playerId);
        liveRaces(eventId);
    }

    public static final class RunningRace {
        private final Player player;
        private final long eventId;
        private final String eventName;
        private final long raceResultId;
        private final long startTime;
        private final Long sessionId;
        private final int laps;
        private final int maxCheckpointOrder;
        private final BossBar bar;
        private final BukkitTask task;
        private int lap = 1;
        private int nextCheckpoint = 1;
        private long lapStartTime;
        private long finishedAt;

        private RunningRace(Player player, long eventId, String eventName, long raceResultId, long startTime,
                            Long sessionId,
                            int laps, int maxCheckpointOrder, BossBar bar, BukkitTask task) {
            this.player = player;
            this.eventId = eventId;
            this.eventName = eventName;
            this.raceResultId = raceResultId;
            this.startTime = startTime;
            this.sessionId = sessionId;
            this.laps = laps;
            this.maxCheckpointOrder = maxCheckpointOrder;
            this.bar = bar;
            this.task = task;
            this.lapStartTime = startTime;
        }

        public long eventId() { return eventId; }
        public Player player() { return player; }
        public long finishedAt() { return finishedAt; }
        public int checkpointProgress() {
            return nextCheckpoint == 0 ? maxCheckpointOrder : nextCheckpoint - 1;
        }
        public String eventName() { return eventName; }
        public long raceResultId() { return raceResultId; }
        public long startTime() { return startTime; }
        public Long sessionId() { return sessionId; }
        public int laps() { return laps; }
        public int maxCheckpointOrder() { return maxCheckpointOrder; }
        public int lap() { return lap; }
        public long lapStartTime() { return lapStartTime; }
        public int nextCheckpoint() { return nextCheckpoint; }
        public boolean checkpointsComplete() {
            return maxCheckpointOrder == 0 || nextCheckpoint == 0;
        }
        public void completeCheckpoint() {
            if (nextCheckpoint < maxCheckpointOrder) nextCheckpoint++;
            else nextCheckpoint = 0;
        }

        /** Called by the end-trigger logic after the current lap has ended. */
        public int completeLap(long completedAt) {
            if (!checkpointsComplete()) return -1;
            int completedLap = lap;
            lapStartTime = completedAt;
            if (lap < laps) {
                lap++;
                nextCheckpoint = maxCheckpointOrder > 0 ? 1 : 0;
            } else nextCheckpoint = 0;
            return completedLap;
        }

        private void stop() {
            task.cancel();
            player.hideBossBar(bar);
        }
    }
}
