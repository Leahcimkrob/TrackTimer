package de.ethria.trackTimer.race;

import de.ethria.trackTimer.database.DatabaseManager;
import de.ethria.trackTimer.database.DatabaseManager.RedstoneStartPoint;
import de.ethria.trackTimer.language.LanguageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/** Opens one shared start-time session when a configured redstone trigger turns on. */
public final class RedstoneStartListener implements Listener {
    private final JavaPlugin plugin;
    private final DatabaseManager database;
    private final LanguageManager language;
    private final Map<Long, BukkitTask> joinTimeouts = new HashMap<>();

    public RedstoneStartListener(JavaPlugin plugin, DatabaseManager database, LanguageManager language) {
        this.plugin = plugin;
        this.database = database;
        this.language = language;
    }

    @EventHandler(ignoreCancelled = true)
    public void onRedstoneChange(BlockRedstoneEvent event) {
        if (event.getOldCurrent() > 0 || event.getNewCurrent() <= 0) return;
        Block block = event.getBlock();
        try {
            for (RedstoneStartPoint trigger : database.findRedstoneStartTriggers(
                    plugin.getServer().getName(), block.getWorld().getName(),
                    block.getX(), block.getY(), block.getZ())) {
                var session = database.activateRedstoneSession(trigger.eventId(), System.currentTimeMillis());
                scheduleJoinTimeout(session);
                Title title = Title.title(language.chatFragment("race.redstone-start-title"), Component.empty());
                var center = block.getLocation().add(0.5, 0.5, 0.5);
                double radius = Math.max(0,
                        plugin.getConfig().getDouble("race.redstone-start-title-radius-blocks", 50));
                double radiusSquared = radius * radius;
                for (var player : block.getWorld().getPlayers()) {
                    if (player.getLocation().distanceSquared(center) <= radiusSquared) player.showTitle(title);
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not activate a redstone start session.", exception);
        }
    }

    private void scheduleJoinTimeout(DatabaseManager.RedstoneSession session) {
        long timeoutSeconds = plugin.getConfig().getLong("race.redstone-start-join-timeout-seconds", 30L);
        BukkitTask previous = joinTimeouts.remove(session.eventId());
        if (previous != null) previous.cancel();
        if (timeoutSeconds <= 0 || session.startedCount() > 0) return;

        long delayTicks = timeoutSeconds > Long.MAX_VALUE / 20 ? Long.MAX_VALUE : timeoutSeconds * 20;
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            joinTimeouts.remove(session.eventId());
            try {
                database.expireUnjoinedRedstoneSession(session.id(), session.startTime());
            } catch (SQLException exception) {
                plugin.getLogger().log(Level.SEVERE,
                        "Could not expire an unused redstone start session.", exception);
            }
        }, delayTicks);
        joinTimeouts.put(session.eventId(), task);
    }
}
