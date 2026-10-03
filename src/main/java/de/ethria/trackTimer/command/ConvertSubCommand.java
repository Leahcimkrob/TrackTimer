package de.ethria.trackTimer.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import de.ethria.trackTimer.database.DatabaseManager;
import de.ethria.trackTimer.language.LanguageManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;

import java.sql.SQLException;
import java.util.List;

/** Copies TrackTimer data between the configured SQLite and MySQL databases. */
public final class ConvertSubCommand implements SubCommand {
    private static final String PERMISSION = "tracktimer.command.convert";
    private static final List<String> DATABASE_TYPES = List.of("sqlite", "mysql");

    private final DatabaseManager database;
    private final LanguageManager language;

    public ConvertSubCommand(DatabaseManager database, LanguageManager language) {
        this.database = database;
        this.language = language;
    }

    @Override public String name() { return "convert"; }
    @Override public String permission() { return PERMISSION; }

    @Override
    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal(name())
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .then(Commands.argument("from", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            String remaining = builder.getRemainingLowerCase();
                            DATABASE_TYPES.stream().filter(type -> type.startsWith(remaining)).forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .then(Commands.argument("to", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    String from = StringArgumentType.getString(context, "from").toLowerCase();
                                    String remaining = builder.getRemainingLowerCase();
                                    DATABASE_TYPES.stream().filter(type -> !type.equals(from) && type.startsWith(remaining))
                                            .forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(context -> {
                                    execute(context.getSource().getSender(),
                                            StringArgumentType.getString(context, "from"),
                                            StringArgumentType.getString(context, "to"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .build();
    }

    private void execute(CommandSender sender, String from, String to) {
        try {
            database.convert(from, to);
            sender.sendMessage(language.chat("database.converted", LanguageManager.placeholders("from", from, "to", to)));
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(language.chat("database.convert-invalid"));
        } catch (SQLException exception) {
            sender.sendMessage(language.chat("database.convert-failed",
                    LanguageManager.placeholders("message", exception.getMessage() == null ? "unknown" : exception.getMessage())));
        }
    }
}
