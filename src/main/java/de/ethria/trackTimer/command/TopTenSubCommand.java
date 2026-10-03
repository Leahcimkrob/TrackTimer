package de.ethria.trackTimer.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import de.ethria.trackTimer.TrackTimer;
import de.ethria.trackTimer.database.DatabaseManager;
import de.ethria.trackTimer.database.DatabaseManager.Event;
import de.ethria.trackTimer.database.DatabaseManager.RaceStatisticsEntry;
import de.ethria.trackTimer.language.LanguageManager;
import de.ethria.trackTimer.race.RaceStatisticsEvaluator;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** {@code /tracktimer topten <event> [date time]} - displays configured race statistics. */
public final class TopTenSubCommand implements SubCommand {
    private static final String PERMISSION = "tracktimer.command.topten";
    private static final DateTimeFormatter DATE_TIME_SUGGESTION_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private final TrackTimer plugin;
    private final DatabaseManager database;
    private final LanguageManager language;
    private final RaceStatisticsEvaluator statistics;

    public TopTenSubCommand(TrackTimer plugin, DatabaseManager database, LanguageManager language,
                            RaceStatisticsEvaluator statistics) {
        this.plugin = plugin;
        this.database = database;
        this.language = language;
        this.statistics = statistics;
    }

    @Override public String name() { return "topten"; }
    @Override public String permission() { return PERMISSION; }

    @Override
    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal(name())
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .then(Commands.argument("eventname", StringArgumentType.word())
                        .suggests((context, builder) -> suggestEvents(builder))
                        .executes(context -> execute(context.getSource().getSender(),
                                StringArgumentType.getString(context, "eventname"), null))
                        .then(Commands.argument("dateTime", StringArgumentType.greedyString())
                                .suggests((context, builder) -> suggestDatesAndTimes(
                                        StringArgumentType.getString(context, "eventname"), builder))
                                .executes(context -> execute(context.getSource().getSender(),
                                        StringArgumentType.getString(context, "eventname"),
                                        StringArgumentType.getString(context, "dateTime")))))
                .build();
    }

    private int execute(CommandSender sender, String eventName, String dateTime) {
        try {
            Event event = findEvent(eventName);
            if (event == null) {
                sender.sendMessage(language.chat("event.not-found", LanguageManager.placeholders("event", eventName)));
                return Command.SINGLE_SUCCESS;
            }

            LocalDate date = null;
            LocalTime time = null;
            if (dateTime != null) {
                if (!"signal".equals(event.startMode())) {
                    sender.sendMessage(language.chat("race-statistics.redstone-filter-only"));
                    return Command.SINGLE_SUCCESS;
                }
                try {
                    String[] dateAndTime = dateTime.trim().split("\\s+", 2);
                    if (dateAndTime.length != 2) {
                        throw new DateTimeParseException("Date and time are both required", dateTime, 0);
                    }
                    date = LocalDate.parse(dateAndTime[0], DateTimeFormatter.ISO_LOCAL_DATE);
                    time = LocalTime.parse(dateAndTime[1], DateTimeFormatter.ISO_LOCAL_TIME).withNano(0);
                } catch (DateTimeParseException invalidDateTime) {
                    sender.sendMessage(language.chat("race-statistics.invalid-date-or-time"));
                    return Command.SINGLE_SUCCESS;
                }
            }
            sendStatistics(sender, event.id(), date, time);
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not display top-ten statistics for event '" + eventName + "'.", exception);
            sender.sendMessage(language.chat("race-statistics.command-failed"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private void sendStatistics(CommandSender audience, long eventId, LocalDate dateFilter,
                                LocalTime timeFilter) throws SQLException {
        var optionalEvaluation = statistics.evaluate(eventId, RaceStatisticsEvaluator.Output.CHAT,
                dateFilter, timeFilter);
        if (optionalEvaluation.isEmpty()) return;
        var evaluation = optionalEvaluation.get();

        if (!evaluation.redstone() || evaluation.entries().isEmpty()) {
            audience.sendMessage(language.chat("race-statistics.chat-title",
                    LanguageManager.placeholders("event", evaluation.event().name())));
        }
        if (evaluation.entries().isEmpty()) {
            audience.sendMessage(language.chat(dateFilter == null && timeFilter == null
                    ? "race-statistics.chat-empty" : "race-statistics.chat-filter-empty"));
            return;
        }

        Locale locale = Locale.forLanguageTag(language.getLocale());
        ZoneId zone = ZoneId.systemDefault();
        DateTimeFormatter dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(locale).withZone(zone);
        DateTimeFormatter timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
                .withLocale(locale).withZone(zone);
        int rank = 1;
        long currentRedstoneStart = Long.MIN_VALUE;
        for (RaceStatisticsEntry entry : evaluation.entries()) {
            String duration = formatDuration(entry.raceTimeMillis());
            Component line;
            if (evaluation.redstone()) {
                long sessionStart = Math.floorDiv(entry.startTimeMillis(), 1000);
                if (sessionStart != currentRedstoneStart) {
                    var sessionStarted = Instant.ofEpochMilli(entry.startTimeMillis());
                    audience.sendMessage(language.chat("race-statistics.redstone-chat-title",
                            LanguageManager.placeholders("event", evaluation.event().name(),
                                    "date", dateFormat.format(sessionStarted),
                                    "time_of_day", timeFormat.format(sessionStarted))));
                    currentRedstoneStart = sessionStart;
                    rank = 1;
                }
                line = language.chatFragment("race-statistics.redstone-entry", LanguageManager.placeholders(
                        "rank", rank, "event", entry.eventName(), "player", entry.playerName(),
                        "race_time", duration, "laps", entry.lapsCompleted()));
            } else {
                line = language.chatFragment("race-statistics.normal-entry", LanguageManager.placeholders(
                        "rank", rank, "event", entry.eventName(), "player", entry.playerName(),
                        "race_time", duration, "laps", entry.lapsCompleted(),
                        "races", evaluation.racesDrivenBy(entry.playerUuid())));
            }
            audience.sendMessage(line);
            rank++;
        }
    }

    private String formatDuration(long durationMillis) {
        long time = Math.max(0, durationMillis);
        Component formatted = language.chatFragment("race-statistics.duration-format", LanguageManager.placeholders(
                "hours", String.format(Locale.ROOT, "%02d", time / 3_600_000),
                "minutes", String.format(Locale.ROOT, "%02d", time / 60_000 % 60),
                "seconds", String.format(Locale.ROOT, "%02d", time / 1_000 % 60),
                "centiseconds", String.format(Locale.ROOT, "%02d", time / 10 % 100)));
        return PlainTextComponentSerializer.plainText().serialize(formatted);
    }

    private CompletableFuture<Suggestions> suggestEvents(SuggestionsBuilder builder) {
        try {
            database.listEvents().stream().map(Event::name)
                    .filter(name -> matchesPrefix(name, builder.getRemaining()))
                    .forEach(builder::suggest);
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not suggest event names for the topten command.", exception);
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestDatesAndTimes(String eventName, SuggestionsBuilder builder) {
        try {
            Event event = findEvent(eventName);
            if (event == null || !"signal".equals(event.startMode())) return builder.buildFuture();
            ZoneId zone = ZoneId.systemDefault();
            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (long timestamp : statistics.recentRedstoneRaceStartTimes(
                    event.id(), RaceStatisticsEvaluator.Output.CHAT)) {
                var localDateTime = Instant.ofEpochMilli(timestamp).atZone(zone);
                values.add(DATE_TIME_SUGGESTION_FORMAT.format(localDateTime));
            }
            values.stream().filter(value -> matchesPrefix(value, builder.getRemaining()))
                    .forEach(builder::suggest);
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not suggest redstone race dates and times.", exception);
        }
        return builder.buildFuture();
    }

    private Event findEvent(String eventName) throws SQLException {
        return database.listEvents().stream()
                .filter(event -> event.name().equals(eventName))
                .findFirst().orElse(null);
    }

    private boolean matchesPrefix(String candidate, String prefix) {
        return candidate.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
