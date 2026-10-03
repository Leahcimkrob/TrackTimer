package de.ethria.trackTimer.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import de.ethria.trackTimer.TrackTimer;
import de.ethria.trackTimer.database.DatabaseManager;
import de.ethria.trackTimer.database.DatabaseManager.Event;
import de.ethria.trackTimer.language.LanguageManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/** {@code /tracktimer delete <eventname>} - asks for chat confirmation before deleting an event. */
public final class DeleteSubCommand implements SubCommand {
    private static final String PERMISSION = "tracktimer.command.delete";
    private static final long CONFIRMATION_WINDOW_MILLIS = 30_000L;

    private final TrackTimer plugin;
    private final DatabaseManager database;
    private final LanguageManager language;
    private final Map<String, PendingDelete> pendingDeletes = new HashMap<>();

    public DeleteSubCommand(TrackTimer plugin, DatabaseManager database, LanguageManager language) {
        this.plugin = plugin;
        this.database = database;
        this.language = language;
    }

    @Override public String name() { return "delete"; }
    @Override public String permission() { return PERMISSION; }

    @Override
    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal(name())
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .then(Commands.argument("eventname", StringArgumentType.word())
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
            pendingDeletes.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis < now);
            Event event = database.listEvents().stream()
                    .filter(candidate -> candidate.name().equals(eventName))
                    .findFirst().orElse(null);
            if (event == null) {
                sender.sendMessage(language.chat("event.not-found", LanguageManager.placeholders("event", eventName)));
                return Command.SINGLE_SUCCESS;
            }

            pendingDeletes.put(senderKey(sender), new PendingDelete(event.id(), event.name(),
                    System.currentTimeMillis() + CONFIRMATION_WINDOW_MILLIS));
            sender.sendMessage(language.chat("event.delete-confirmation",
                    LanguageManager.placeholders("event", event.name(),
                            "confirm_command", "/tracktimer delete " + event.name() + " confirm",
                            "cancel_command", "/tracktimer delete " + event.name() + " cancel")));
        } catch (SQLException exception) {
            reportDatabaseError(sender, eventName, exception);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int confirm(CommandSender sender, String eventName) {
        PendingDelete pending = pendingDeletes.get(senderKey(sender));
        if (pending == null || !pending.eventName.equals(eventName)) {
            sender.sendMessage(language.chat("event.delete-confirmation-missing"));
            return Command.SINGLE_SUCCESS;
        }
        pendingDeletes.remove(senderKey(sender));
        if (System.currentTimeMillis() > pending.expiresAtMillis) {
            sender.sendMessage(language.chat("event.delete-confirmation-expired",
                    LanguageManager.placeholders("event", pending.eventName)));
            return Command.SINGLE_SUCCESS;
        }

        try {
            Event currentEvent = database.listEvents().stream()
                    .filter(candidate -> candidate.id() == pending.eventId)
                    .findFirst().orElse(null);
            if (currentEvent == null || !currentEvent.name().equals(pending.eventName)) {
                sender.sendMessage(language.chat("event.delete-confirmation-missing"));
                return Command.SINGLE_SUCCESS;
            }
            database.deleteEvent(pending.eventId);
            sender.sendMessage(language.chat("event.deleted",
                    LanguageManager.placeholders("event", pending.eventName)));
        } catch (SQLException exception) {
            reportDatabaseError(sender, pending.eventName, exception);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int cancel(CommandSender sender, String eventName) {
        PendingDelete pending = pendingDeletes.get(senderKey(sender));
        if (pending == null || !pending.eventName.equals(eventName)) {
            sender.sendMessage(language.chat("event.delete-confirmation-missing"));
            return Command.SINGLE_SUCCESS;
        }
        pendingDeletes.remove(senderKey(sender));
        sender.sendMessage(language.chat("event.delete-cancelled"));
        return Command.SINGLE_SUCCESS;
    }

    private String senderKey(CommandSender sender) {
        if (sender instanceof Player player) return "player:" + player.getUniqueId();
        return "sender:" + sender.getName();
    }

    private void reportDatabaseError(CommandSender sender, String eventName, SQLException exception) {
        plugin.getLogger().log(Level.SEVERE, "Could not delete event '" + eventName + "'.", exception);
        sender.sendMessage(language.chat("event.delete-failed",
                LanguageManager.placeholders("event", eventName)));
    }

    private record PendingDelete(long eventId, String eventName, long expiresAtMillis) { }
}
