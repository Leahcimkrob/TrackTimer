package de.ethria.trackTimer.race;

import de.ethria.trackTimer.database.DatabaseManager;
import de.ethria.trackTimer.database.DatabaseManager.Event;
import de.ethria.trackTimer.database.DatabaseManager.RaceStatisticsEntry;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Evaluates per-player best times or most recent redstone runs for each output. */
public final class RaceStatisticsEvaluator {
    public enum Output {
        GUI("gui"), HOLOGRAM("hologram"), CHAT("chat");

        private final String configKey;

        Output(String configKey) {
            this.configKey = configKey;
        }
    }

    public record Evaluation(Event event, boolean redstone, List<RaceStatisticsEntry> entries,
                             Map<String, Integer> racesPerPlayer) {
        public int racesDrivenBy(String playerUuid) {
            return racesPerPlayer.getOrDefault(playerUuid, 0);
        }
    }

    private final JavaPlugin plugin;
    private final DatabaseManager database;
    public RaceStatisticsEvaluator(JavaPlugin plugin, DatabaseManager database) {
        this.plugin = plugin;
        this.database = database;
    }

    /** Returns best per-player results for normal races or all participants for selected redstone races. */
    public Optional<Evaluation> evaluate(long eventId, Output output) throws SQLException {
        return evaluate(eventId, output, null, null, null);
    }

    public Optional<Evaluation> evaluate(long eventId, Output output, LocalDate dateFilter,
                                         LocalTime timeFilter) throws SQLException {
        return evaluate(eventId, output, dateFilter, timeFilter, null);
    }

    public Optional<Evaluation> evaluate(long eventId, Output output, LocalDate dateFilter,
                                         LocalTime timeFilter, Boolean redstoneView) throws SQLException {
        Event event = database.getEvent(eventId);
        if (event == null) return Optional.empty();

        boolean redstone = redstoneView != null ? redstoneView : "signal".equals(event.startMode());
        String countKey = redstone ? "redstone-races" : "normal-races";
        int defaultCount = defaultCount(output, redstone);
        int limit = Math.max(0, plugin.getConfig().getInt(
                "race-statistics." + output.configKey + "." + countKey, defaultCount));
        if (limit == 0) return Optional.of(new Evaluation(event, redstone, List.of(), Map.of()));

        Comparator<RaceStatisticsEntry> normalOrder = Comparator.comparingLong(RaceStatisticsEntry::raceTimeMillis)
                    .thenComparingLong(RaceStatisticsEntry::startTimeMillis)
                    .thenComparingLong(RaceStatisticsEntry::resultId);

        List<RaceStatisticsEntry> completedRaces = database.listCompletedRaceStatistics(eventId);
        Map<String, Integer> racesPerPlayer = new LinkedHashMap<>();
        completedRaces.stream().filter(entry -> !redstone || entry.redstoneStart())
                .forEach(entry -> racesPerPlayer.merge(entry.playerUuid(), 1, Integer::sum));
        ZoneId zone = ZoneId.systemDefault();
        List<RaceStatisticsEntry> selected;
        if (redstone) {
            List<RaceStatisticsEntry> filtered = completedRaces.stream()
                    .filter(RaceStatisticsEntry::redstoneStart)
                    .filter(entry -> matchesDateTime(entry, dateFilter, timeFilter, zone))
                    .toList();
            var selectedStarts = new java.util.LinkedHashSet<Long>();
            if (dateFilter != null || timeFilter != null) {
                filtered.forEach(entry -> selectedStarts.add(Math.floorDiv(entry.startTimeMillis(), 1000)));
            } else {
                filtered.stream().map(entry -> Math.floorDiv(entry.startTimeMillis(), 1000))
                        .distinct().sorted(Comparator.reverseOrder()).limit(limit)
                        .forEach(selectedStarts::add);
            }
            Comparator<RaceStatisticsEntry> sessionOrder =
                    Comparator.comparingLong(RaceStatisticsEntry::startTimeMillis);
            if (!redstoneSessionsAscending(output)) sessionOrder = sessionOrder.reversed();
            Comparator<RaceStatisticsEntry> redstoneOrder = sessionOrder
                    .thenComparingLong(RaceStatisticsEntry::raceTimeMillis)
                    .thenComparingLong(RaceStatisticsEntry::resultId);
            selected = filtered.stream()
                    .filter(entry -> selectedStarts.contains(Math.floorDiv(entry.startTimeMillis(), 1000)))
                    .sorted(redstoneOrder)
                    .toList();
        } else {
            Map<String, RaceStatisticsEntry> bestPerPlayer = new LinkedHashMap<>();
            completedRaces.stream().sorted(normalOrder)
                    .forEach(entry -> bestPerPlayer.putIfAbsent(entry.playerUuid(), entry));
            selected = new ArrayList<>(bestPerPlayer.values());
            if (selected.size() > limit) selected = new ArrayList<>(selected.subList(0, limit));
        }
        return Optional.of(new Evaluation(event, redstone, List.copyOf(selected), Map.copyOf(racesPerPlayer)));
    }

    /** Returns the most recent distinct redstone race start timestamps for tab completion. */
    public List<Long> recentRedstoneRaceStartTimes(long eventId, Output output) throws SQLException {
        Event event = database.getEvent(eventId);
        if (event == null || !"signal".equals(event.startMode())) return List.of();

        int limit = Math.max(0, plugin.getConfig().getInt(
                "race-statistics." + output.configKey + ".redstone-races", defaultCount(output, true)));
        if (limit == 0) return List.of();

        return database.listCompletedRaceStatistics(eventId).stream()
                .filter(RaceStatisticsEntry::redstoneStart)
                .map(RaceStatisticsEntry::startTimeMillis)
                .map(timestamp -> Math.floorDiv(timestamp, 1000))
                .distinct()
                .sorted(Comparator.reverseOrder())
                .limit(limit)
                .map(timestamp -> timestamp * 1000)
                .toList();
    }

    private boolean matchesDateTime(RaceStatisticsEntry entry, LocalDate dateFilter,
                                    LocalTime timeFilter, ZoneId zone) {
        if (dateFilter == null && timeFilter == null) return true;
        var localDateTime = Instant.ofEpochMilli(entry.startTimeMillis()).atZone(zone);
        return (dateFilter == null || localDateTime.toLocalDate().equals(dateFilter))
                && (timeFilter == null || localDateTime.toLocalTime().withNano(0).equals(timeFilter));
    }

    private int defaultCount(Output output, boolean redstone) {
        if (redstone) return 20;
        return 10;
    }

    private boolean redstoneSessionsAscending(Output output) {
        return (output == Output.CHAT || output == Output.HOLOGRAM)
                && "ascending".equalsIgnoreCase(
                plugin.getConfig().getString("race-statistics.chat.redstone-sort-order", "descending"));
    }
}
