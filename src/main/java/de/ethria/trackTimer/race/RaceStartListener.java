package de.ethria.trackTimer.race;

import de.ethria.trackTimer.database.DatabaseManager;
import de.ethria.trackTimer.database.DatabaseManager.StartPoint;
import de.ethria.trackTimer.language.LanguageManager;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.bossbar.BossBar.Color;
import net.kyori.adventure.bossbar.BossBar.Overlay;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import java.util.logging.Level;

/** Detects normal player start blocks and displays the elapsed race time. */
public final class RaceStartListener implements Listener {
    private final JavaPlugin plugin;
    private final DatabaseManager database;
    private final LanguageManager language;
    private final Map<UUID, Map<Long, RunningRace>> running = new HashMap<>();
    private final Map<UUID, BlockPosition> lastVehiclePositions = new HashMap<>();
    private final BukkitTask vehicleMonitor;

    public RaceStartListener(JavaPlugin plugin, DatabaseManager database, LanguageManager language) {
        this.plugin = plugin;
        this.database = database;
        this.language = language;
        vehicleMonitor = Bukkit.getScheduler().runTaskTimer(plugin, this::checkRidingPlayers, 1L, 1L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || !event.hasChangedBlock()) return;
        checkStartAt(event.getPlayer(), event.getTo());
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) return;
        for (var passenger : event.getVehicle().getPassengers()) {
            if (passenger instanceof Player player) {
                Location playerPosition = player.getLocation();
                playerPosition.setX(to.getX());
                playerPosition.setZ(to.getZ());
                checkStartAt(player, playerPosition);
            }
        }
    }

    private void checkStartAt(Player player, Location position) {
        double tolerance = plugin.getConfig().getDouble("race.start-trigger-height-tolerance", 2.0);
        if (!Double.isFinite(tolerance) || tolerance < 0) tolerance = 2.0;
        int minY = (int) Math.floor(position.getY() - tolerance) - 1;
        int maxY = (int) Math.ceil(position.getY() + tolerance);
        try {
            for (StartPoint point : database.findPlayerStartPoints(plugin.getServer().getName(),
                    position.getWorld().getName(), position.getBlockX(), position.getBlockZ(), minY, maxY)) {
                double triggerY = point.triggerY();
                double distanceToSurface = Math.min(Math.abs(position.getY() - triggerY),
                        Math.abs(position.getY() - (triggerY + 1.0)));
                if (distanceToSurface > tolerance || isRunning(player, point.eventId())) continue;
                long started = database.beginRace(point.eventId(), player.getUniqueId().toString(), player.getName());
                beginDisplay(player, point.eventId(), point.eventName(), started);
            }
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not start race for " + player.getName(), exception);
        }
    }

    /** Polls only vehicle passengers, including custom vehicles that do not emit VehicleMoveEvent. */
    private void checkRidingPlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getVehicle() == null) {
                lastVehiclePositions.remove(player.getUniqueId());
                continue;
            }
            Location position = player.getLocation();
            BlockPosition current = new BlockPosition(position.getBlockX(), position.getBlockY(), position.getBlockZ());
            BlockPosition previous = lastVehiclePositions.put(player.getUniqueId(), current);
            if (!current.equals(previous)) checkStartAt(player, position);
        }
    }

    private boolean isRunning(Player player, long eventId) {
        return running.getOrDefault(player.getUniqueId(), Map.of()).containsKey(eventId);
    }

    private void beginDisplay(Player player, long eventId, String eventName, long startTime) {
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
                .put(eventId, new RunningRace(player, bar, task));
    }

    private Component format(long startTime, long now) {
        long elapsedMillis = Math.max(0, now - startTime);
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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastVehiclePositions.remove(event.getPlayer().getUniqueId());
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
        if (races != null) races.values().forEach(RunningRace::stop);
    }

    public void shutdown() {
        vehicleMonitor.cancel();
        lastVehiclePositions.clear();
        running.values().forEach(races -> races.values().forEach(RunningRace::stop));
        running.clear();
    }

    private void stopDisplay(UUID playerId, long eventId) {
        Map<Long, RunningRace> races = running.get(playerId);
        if (races == null) return;
        RunningRace race = races.remove(eventId);
        if (race != null) race.stop();
        if (races.isEmpty()) running.remove(playerId);
    }

    private record RunningRace(Player player, BossBar bar, BukkitTask task) {
        private void stop() {
            task.cancel();
            player.hideBossBar(bar);
        }
    }

    private record BlockPosition(int x, int y, int z) { }
}
