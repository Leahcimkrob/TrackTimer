package de.ethria.trackTimer.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import de.ethria.trackTimer.TrackTimer;
import de.ethria.trackTimer.database.DatabaseManager;
import de.ethria.trackTimer.database.DatabaseManager.Event;
import de.ethria.trackTimer.language.LanguageManager;
import de.ethria.trackTimer.race.RaceStatisticsEvaluator;
import de.ethria.trackTimer.tools.RaceStatisticsHologramDeleteTool;
import de.ethria.trackTimer.tools.RaceStatisticsHologramTool;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** Creates area-selection and removal tools for race statistics holograms. */
public final class HologramSubCommand implements SubCommand {
    private static final String PERMISSION = "tracktimer.command.hologram";
    private static final DateTimeFormatter DATE_TIME_SUGGESTION_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private final TrackTimer plugin;
    private final DatabaseManager database;
    private final LanguageManager language;
    private final RaceStatisticsEvaluator statistics;
    private final RaceStatisticsHologramTool createTool;
    private final RaceStatisticsHologramDeleteTool removeTool;

    public HologramSubCommand(TrackTimer plugin, DatabaseManager database, LanguageManager language,
                             RaceStatisticsEvaluator statistics, RaceStatisticsHologramTool createTool,
                             RaceStatisticsHologramDeleteTool removeTool) {
        this.plugin = plugin;
        this.database = database;
        this.language = language;
        this.statistics = statistics;
        this.createTool = createTool;
        this.removeTool = removeTool;
    }

    @Override public String name() { return "hologram"; }
    @Override public String permission() { return PERMISSION; }

    @Override
    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal(name())
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(context -> {
                    context.getSource().getSender().sendMessage(language.chat("help.hologram",
                            LanguageManager.placeholders("command", "tracktimer")));
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("add")
                        .then(Commands.argument("eventname", StringArgumentType.word())
                                .suggests((context, builder) -> suggestEvents(builder))
                                .executes(context -> executeAdd(context.getSource().getSender(),
                                        StringArgumentType.getString(context, "eventname"), null))
                                .then(Commands.argument("dateTime", StringArgumentType.greedyString())
                                        .suggests((context, builder) -> suggestDatesAndTimes(
                                                StringArgumentType.getString(context, "eventname"), builder))
                                        .executes(context -> executeAdd(context.getSource().getSender(),
                                                StringArgumentType.getString(context, "eventname"),
                                                StringArgumentType.getString(context, "dateTime"))))))
                .then(Commands.literal("remove")
                        .executes(context -> executeRemove(context.getSource().getSender())))
                .then(Commands.literal("reload")
                        .executes(context -> executeReload(context.getSource().getSender())))
                .build();
    }

    private int executeAdd(CommandSender sender, String eventName, String dateTime) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(language.chat("gui.player-only"));
            return Command.SINGLE_SUCCESS;
        }
        if (createTool == null) {
            player.sendMessage(language.chat("race-statistics.hologram-plugin-missing"));
            return Command.SINGLE_SUCCESS;
        }
        try {
            Event event = findEvent(eventName);
            if (event == null) {
                player.sendMessage(language.chat("event.not-found", LanguageManager.placeholders("event", eventName)));
                return Command.SINGLE_SUCCESS;
            }
            LocalDate date = null;
            LocalTime time = null;
            if (dateTime != null) {
                try {
                    String[] dateAndTime = dateTime.trim().split("\\s+", 2);
                    date = LocalDate.parse(dateAndTime[0], DateTimeFormatter.ISO_LOCAL_DATE);
                    if (dateAndTime.length == 2) {
                        time = LocalTime.parse(dateAndTime[1], DateTimeFormatter.ISO_LOCAL_TIME).withNano(0);
                    }
                } catch (DateTimeParseException invalidDateTime) {
                    player.sendMessage(language.chat("race-statistics.invalid-date-or-time"));
                    return Command.SINGLE_SUCCESS;
                }
            }
            createTool.giveTool(player, event, date != null, date, time);
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not prepare the race hologram tool for '"
                    + eventName + "'.", exception);
            player.sendMessage(language.chat("race-statistics.command-failed"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int executeReload(CommandSender sender) {
        try {
            plugin.reloadConfig();
            language.load();
            plugin.reloadHolograms();
            sender.sendMessage(language.chat("race-statistics.holograms-reloaded"));
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not reload race holograms.", exception);
            sender.sendMessage(language.chat("race-statistics.command-failed"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int executeRemove(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(language.chat("gui.player-only"));
            return Command.SINGLE_SUCCESS;
        }
        if (removeTool == null) {
            player.sendMessage(language.chat("race-statistics.hologram-plugin-missing"));
            return Command.SINGLE_SUCCESS;
        }
        removeTool.giveTool(player);
        return Command.SINGLE_SUCCESS;
    }

    private CompletableFuture<Suggestions> suggestEvents(SuggestionsBuilder builder) {
        try {
            database.listEvents().stream().map(Event::name)
                    .filter(name -> matchesPrefix(name, builder.getRemaining()))
                    .forEach(builder::suggest);
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not suggest event names for the hologram command.", exception);
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestDatesAndTimes(String eventName, SuggestionsBuilder builder) {
        try {
            Event event = findEvent(eventName);
            if (event == null) return builder.buildFuture();
            ZoneId zone = ZoneId.systemDefault();
            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (long timestamp : statistics.recentRedstoneRaceStartTimes(
                    event.id(), RaceStatisticsEvaluator.Output.HOLOGRAM)) {
                values.add(DATE_TIME_SUGGESTION_FORMAT.format(Instant.ofEpochMilli(timestamp).atZone(zone)));
            }
            values.stream().filter(value -> matchesPrefix(value, builder.getRemaining())).forEach(builder::suggest);
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not suggest race dates and times for hologram command.", exception);
        }
        return builder.buildFuture();
    }

    private Event findEvent(String eventName) throws SQLException {
        return database.listEvents().stream().filter(event -> event.name().equals(eventName)).findFirst().orElse(null);
    }

    private boolean matchesPrefix(String candidate, String prefix) {
        return candidate.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
