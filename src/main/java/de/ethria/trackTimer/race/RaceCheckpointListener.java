package de.ethria.trackTimer.race;

import de.ethria.trackTimer.database.DatabaseManager;
import de.ethria.trackTimer.database.DatabaseManager.CheckpointPoint;
import de.ethria.trackTimer.language.LanguageManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.logging.Level;

/** Records only the next expected checkpoint for each active player and lap. */
public final class RaceCheckpointListener {
    private final JavaPlugin plugin;
    private final DatabaseManager database;
    private final LanguageManager language;
    private final RaceStartListener races;

    public RaceCheckpointListener(JavaPlugin plugin, DatabaseManager database, LanguageManager language,
                                  RaceStartListener races) {
        this.plugin = plugin;
        this.database = database;
        this.language = language;
        this.races = races;
    }

    public void onRacePosition(Player player, Location position) {
        var activeRaces = races.activeRaces(player);
        if (activeRaces.isEmpty()) return;

        double tolerance = TriggerPositionMatcher.heightTolerance(plugin);
        int minY = TriggerPositionMatcher.minY(position, tolerance);
        int maxY = TriggerPositionMatcher.maxY(position, tolerance);
        try {
            for (CheckpointPoint checkpoint : database.findCheckpointTriggers(plugin.getServer().getName(),
                    position.getWorld().getName(), position.getBlockX(), position.getBlockZ(), minY, maxY)) {
                RaceStartListener.RunningRace race = races.activeRace(player, checkpoint.eventId());
                if (race == null || race.nextCheckpoint() < 1
                        || checkpoint.checkpointOrder() != race.nextCheckpoint()
                        || !TriggerPositionMatcher.isWithinHeight(position, checkpoint.triggerY(), tolerance)) continue;

                long elapsedMillis = Math.max(0, System.currentTimeMillis() - race.startTime());
                database.recordCheckpoint(race.raceResultId(), checkpoint.triggerId(), race.lap(), elapsedMillis);
                race.completeCheckpoint();
                player.sendActionBar(language.chatFragment("race.checkpoint", LanguageManager.placeholders(
                        "order", checkpoint.checkpointOrder(),
                        "time", races.formatDuration(elapsedMillis),
                        "lap", race.lap(), "laps", race.laps())));
                break;
            }
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not save checkpoint time for " + player.getName(), exception);
        }
    }
}
