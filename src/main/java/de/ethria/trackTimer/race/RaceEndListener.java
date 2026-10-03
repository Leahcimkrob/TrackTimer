package de.ethria.trackTimer.race;

import de.ethria.trackTimer.database.DatabaseManager;
import de.ethria.trackTimer.database.DatabaseManager.EndPoint;
import de.ethria.trackTimer.database.DatabaseManager.RedstoneSessionSummary;
import de.ethria.trackTimer.language.LanguageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.logging.Level;

/** Completes a lap or race at an end trigger after its checkpoints are finished. */
public final class RaceEndListener {
    private final JavaPlugin plugin;
    private final DatabaseManager database;
    private final LanguageManager language;
    private final RaceStartListener races;
    public RaceEndListener(JavaPlugin plugin, DatabaseManager database, LanguageManager language,
                           RaceStartListener races) {
        this.plugin = plugin;
        this.database = database;
        this.language = language;
        this.races = races;
    }

    public void onRacePosition(Player player, Location position) {
        if (races.activeRaces(player).isEmpty()) return;

        double tolerance = TriggerPositionMatcher.heightTolerance(plugin);
        int minY = TriggerPositionMatcher.minY(position, tolerance);
        int maxY = TriggerPositionMatcher.maxY(position, tolerance);
        try {
            for (EndPoint end : database.findEndTriggers(plugin.getServer().getName(),
                    position.getWorld().getName(), position.getBlockX(), position.getBlockZ(), minY, maxY)) {
                RaceStartListener.RunningRace race = races.activeRace(player, end.eventId());
                if (race == null || !race.checkpointsComplete()
                        || !TriggerPositionMatcher.isWithinHeight(position, end.triggerY(), tolerance)) continue;

                int completedLap = race.lap();
                long now = System.currentTimeMillis();
                long elapsedMillis = Math.max(0, now - race.startTime());
                boolean finished = completedLap >= race.laps();
                database.recordCompletedLap(race.raceResultId(), completedLap,
                        finished ? now : null, finished ? elapsedMillis : null);
                RedstoneSessionSummary sessionSummary = finished
                        ? database.finishRedstoneSessionIfIdle(race.sessionId()) : null;
                race.completeLap();

                if (finished) {
                    races.finishRace(player, end.eventId());
                    player.sendMessage(language.chat("race.finished", LanguageManager.placeholders(
                            "event", race.eventName(), "time", races.formatDuration(elapsedMillis))));
                    if (sessionSummary != null) {
                        var podium = sessionSummary.podium();
                        var lines = language.chatLines("race.redstone-session-finished", LanguageManager.placeholders(
                                "winner", podium.get(0), "second", podium.get(1), "third", podium.get(2)));
                        Component title = lines.isEmpty() ? Component.empty() : lines.get(0);
                        Component subtitle = lines.size() < 2 ? Component.empty() : lines.get(1);
                        Title announcement = Title.title(title, subtitle);
                        for (String participantUuid : sessionSummary.participantUuids()) {
                            Player participant = plugin.getServer().getPlayer(
                                    java.util.UUID.fromString(participantUuid));
                            if (participant != null) participant.showTitle(announcement);
                        }
                    }
                } else {
                    player.sendActionBar(language.chatFragment("race.lap-completed", LanguageManager.placeholders(
                            "lap", completedLap, "laps", race.laps())));
                }
                break;
            }
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not record lap end for " + player.getName(), exception);
        }
    }

}
