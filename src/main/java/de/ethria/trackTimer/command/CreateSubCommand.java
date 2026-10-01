package de.ethria.trackTimer.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import de.ethria.trackTimer.TrackTimer;
import de.ethria.trackTimer.database.DatabaseManager;
import de.ethria.trackTimer.language.LanguageManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;

import java.sql.SQLException;
import java.util.logging.Level;

/**
 * {@code /tracktimer create <eventname> <laps> <mode>} - creates an event.
 */
public final class CreateSubCommand implements SubCommand {

    private static final String PERMISSION = "tracktimer.command.create";

    private final TrackTimer plugin;
    private final DatabaseManager database;
    private final LanguageManager language;

    public CreateSubCommand(TrackTimer plugin, DatabaseManager database, LanguageManager language) {
        this.plugin = plugin;
        this.database = database;
        this.language = language;
    }

    @Override
    public String name() {
        return "create";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal(name())
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .then(Commands.argument("eventname", StringArgumentType.word())
                        .then(Commands.argument("laps", IntegerArgumentType.integer(1))
                                .then(Commands.literal("player")
                                        .executes(context -> execute(
                                                context.getSource().getSender(),
                                                StringArgumentType.getString(context, "eventname"),
                                                IntegerArgumentType.getInteger(context, "laps"),
                                                "player"
                                        )))
                                .then(Commands.literal("signal")
                                        .executes(context -> execute(
                                                context.getSource().getSender(),
                                                StringArgumentType.getString(context, "eventname"),
                                                IntegerArgumentType.getInteger(context, "laps"),
                                                "signal"
                                        )))))
                .build();
    }

    private int execute(CommandSender sender, String eventName, int laps, String mode) {
        if (!DatabaseManager.isValidEventName(eventName)) {
            sender.sendMessage(language.chat("event.invalid-name"));
            return Command.SINGLE_SUCCESS;
        }
        try {
            if (!database.createEvent(eventName, laps, mode)) {
                sender.sendMessage(language.chat("event.already-exists",
                        LanguageManager.placeholders("event", eventName)));
                return Command.SINGLE_SUCCESS;
            }
            sender.sendMessage(language.chat("event.created",
                    LanguageManager.placeholders("event", eventName, "laps", laps, "mode", mode)));
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not create event '" + eventName + "'.", exception);
            sender.sendMessage(language.chat("event.create-failed",
                    LanguageManager.placeholders("event", eventName)));
        }
        return Command.SINGLE_SUCCESS;
    }
}
