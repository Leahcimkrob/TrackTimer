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
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** {@code /tracktimer reset <eventname>} - confirms before clearing race data. */
public final class ResetSubCommand implements SubCommand {
    private static final String PERMISSION = "tracktimer.command.reset";
    private static final long CONFIRMATION_WINDOW_MILLIS = 30_000L;

    private final TrackTimer plugin;
    private final DatabaseManager database;
    private final LanguageManager language;
    private final Map<String, PendingReset> pendingResets = new HashMap<>();

    public ResetSubCommand(TrackTimer plugin, DatabaseManager database, LanguageManager language) {
        this.plugin = plugin;
        this.database = database;
        this.language = language;
    }

    @Override public String name() { return "reset"; }
    @Override public String permission() { return PERMISSION; }

    @Override
    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal(name())
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .then(Commands.argument("eventname", StringArgumentType.word())
                        .suggests((context, builder) -> suggestEvents(builder))
                        .executes(context -> requestConfirmation(
                                context.getSource().getSender(),
                                StringArgumentType.getString(context, "eventname")))
                        .then(Commands.literal("confirm")
                                .executes(context -> confirm(
                                        context.getSource().getSender(),
                                        StringArgumentType.getString(context, "eventname"))))
                        .then(Commands.literal("cancel")
                                .executes(context -> cancel(
                                        context.getSource().getSender(),
                                        StringArgumentType.getString(context, "eventname")))))
                .build();
    }

    private int requestConfirmation(CommandSender sender, String eventName) {
        try {
            long now = System.currentTimeMillis();
            pendingResets.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis < now);
            Event event = findEventByName(eventName);
            if (event == null) {
                sender.sendMessage(language.chat("event.not-found", LanguageManager.placeholders("event", eventName)));
                return Command.SINGLE_SUCCESS;
            }

            pendingResets.put(senderKey(sender), new PendingReset(event.id(), event.name(),
                    now + CONFIRMATION_WINDOW_MILLIS));
            sendEventOverview(sender, event);
            Component confirmation = language.chat("event.reset-confirmation").append(Component.space())
                    .append(language.chatFragment("event.reset-confirm-button")
                            .clickEvent(ClickEvent.runCommand("/tracktimer reset " + event.name() + " confirm")))
                    .append(Component.space())
                    .append(language.chatFragment("event.reset-cancel-button")
                            .clickEvent(ClickEvent.runCommand("/tracktimer reset " + event.name() + " cancel")));
            sender.sendMessage(confirmation);
            sender.sendMessage(language.chat("event.reset-confirm-command",
                    LanguageManager.placeholders("confirm_command", "/tt reset " + event.name() + " confirm")));
        } catch (SQLException exception) {
            reportDatabaseError(sender, eventName, exception);
        }
        return Command.SINGLE_SUCCESS;
    }

    private void sendEventOverview(CommandSender sender, Event event) {
        boolean german = language.getLocale().toLowerCase(java.util.Locale.ROOT).startsWith("de");
        String icon = event.icon().startsWith("b64:") ? (german ? "benutzerdefiniertes Item" : "custom item") : event.icon();
        String startMode = "signal".equals(event.startMode())
                ? (german ? "Redstone-Signal" : "Redstone signal")
                : (german ? "Spieler" : "Player");
        sender.sendMessage(language.chat("event.data-overview", LanguageManager.placeholders(
                "id", event.id(), "event", event.name(), "laps", event.laps(), "start_mode", startMode,
                "icon", icon, "trigger_count", event.triggerCount(), "created", event.created())));
    }

    private int confirm(CommandSender sender, String eventName) {
        PendingReset pending = pendingResets.get(senderKey(sender));
        if (pending == null || !pending.eventName.equals(eventName)) {
            sender.sendMessage(language.chat("event.reset-confirmation-missing"));
            return Command.SINGLE_SUCCESS;
        }
        pendingResets.remove(senderKey(sender));
        if (System.currentTimeMillis() > pending.expiresAtMillis) {
            sender.sendMessage(language.chat("event.reset-confirmation-expired",
                    LanguageManager.placeholders("event", pending.eventName)));
            return Command.SINGLE_SUCCESS;
        }

        try {
            Event currentEvent = findEventById(pending.eventId);
            if (currentEvent == null || !currentEvent.name().equals(pending.eventName)) {
                sender.sendMessage(language.chat("event.reset-confirmation-missing"));
                return Command.SINGLE_SUCCESS;
            }
            database.resetEventResults(pending.eventId);
            sender.sendMessage(language.chat("event.reset-complete",
                    LanguageManager.placeholders("event", pending.eventName)));
        } catch (SQLException exception) {
            reportDatabaseError(sender, pending.eventName, exception);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int cancel(CommandSender sender, String eventName) {
        PendingReset pending = pendingResets.get(senderKey(sender));
        if (pending == null || !pending.eventName.equals(eventName)) {
            sender.sendMessage(language.chat("event.reset-confirmation-missing"));
            return Command.SINGLE_SUCCESS;
        }
        pendingResets.remove(senderKey(sender));
        sender.sendMessage(language.chat("event.reset-cancelled"));
        return Command.SINGLE_SUCCESS;
    }

    private Event findEventByName(String eventName) throws SQLException {
        return database.listEvents().stream()
                .filter(candidate -> candidate.name().equals(eventName))
                .findFirst().orElse(null);
    }

    private Event findEventById(long eventId) throws SQLException {
        return database.listEvents().stream()
                .filter(candidate -> candidate.id() == eventId)
                .findFirst().orElse(null);
    }

    private String senderKey(CommandSender sender) {
        if (sender instanceof Player player) return "player:" + player.getUniqueId();
        return "sender:" + sender.getName();
    }

    private CompletableFuture<Suggestions> suggestEvents(SuggestionsBuilder builder) {
        try {
            database.listEvents().stream()
                    .map(Event::name)
                    .filter(name -> name.regionMatches(true, 0, builder.getRemaining(), 0,
                            builder.getRemaining().length()))
                    .forEach(builder::suggest);
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not suggest event names for the reset command.", exception);
        }
        return builder.buildFuture();
    }

    private void reportDatabaseError(CommandSender sender, String eventName, SQLException exception) {
        plugin.getLogger().log(Level.SEVERE, "Could not reset race data for event '" + eventName + "'.", exception);
        sender.sendMessage(language.chat("event.reset-failed",
                LanguageManager.placeholders("event", eventName)));
    }

    private record PendingReset(long eventId, String eventName, long expiresAtMillis) { }
}
