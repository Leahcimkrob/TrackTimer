package de.ethria.trackTimer.api;

import de.ethria.trackTimer.database.DatabaseManager;
import de.ethria.trackTimer.race.RaceStartListener;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Public read-only API for other plugins integrating with TrackTimer. */
public final class TrackTimerApi {
    private final DatabaseManager database;
    private final RaceStartListener races;

    public TrackTimerApi(DatabaseManager database, RaceStartListener races) {
        this.database = database;
        this.races = races;
    }

    /** Returns the configured event and its current live state, or empty if it does not exist. */
    public Optional<RaceSnapshot> getRace(long eventId) throws SQLException {
        DatabaseManager.Event event = database.getEvent(eventId);
        if (event == null) return Optional.empty();
        return Optional.of(snapshot(event.id(), event.name()));
    }

    /** Returns all configured events and their current live state. */
    public List<RaceSnapshot> getRaces() throws SQLException {
        return database.listEvents().stream().map(event -> snapshot(event.id(), event.name())).toList();
    }

    private RaceSnapshot snapshot(long eventId, String eventName) {
        List<ActivePlayer> players = races.activeRaces(eventId).stream()
                .map(race -> {
                    Player player = race.player();
                    return new ActivePlayer(player.getUniqueId(), player.getName(), player.getLocation());
                })
                .sorted(Comparator.comparing(ActivePlayer::uuid))
                .toList();
        RaceStatus status = !players.isEmpty() ? RaceStatus.RUNNING
                : races.hasFinished(eventId) ? RaceStatus.FINISHED : RaceStatus.NOT_STARTED;
        return new RaceSnapshot(eventId, eventName, status, players);
    }

    public enum RaceStatus {
        /** No participant is currently racing; this includes races that have never started. */
        NOT_STARTED,
        RUNNING,
        FINISHED
    }

    public record RaceSnapshot(long eventId, String eventName, RaceStatus status,
                               List<ActivePlayer> activePlayers) {
        public RaceSnapshot {
            activePlayers = List.copyOf(activePlayers);
        }
    }

    public record ActivePlayer(UUID uuid, String name, Location location) {
        public ActivePlayer {
            location = location.clone();
        }

        @Override
        public Location location() {
            return location.clone();
        }
    }
}
