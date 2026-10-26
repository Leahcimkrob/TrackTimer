package de.ethria.trackTimer.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import de.ethria.trackTimer.database.DatabaseManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

/** Shows the server identifier currently used for database records. */
public final class ServerSubCommand implements SubCommand {

    private static final String PERMISSION = "tracktimer.command.server";
    private final DatabaseManager database;

    public ServerSubCommand(DatabaseManager database) {
        this.database = database;
    }

    @Override
    public String name() {
        return "server";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal(name())
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(context -> {
                    context.getSource().getSender().sendMessage("Database server name: " + database.getServerName());
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
