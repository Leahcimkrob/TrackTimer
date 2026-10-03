package de.ethria.trackTimer.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import de.ethria.trackTimer.gui.EventOverviewGui;
import de.ethria.trackTimer.language.LanguageManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;

/** Opens the public event overview used to browse race results. */
public final class OverviewSubCommand implements SubCommand {
    private static final String PERMISSION = "tracktimer.command.overview";
    private final EventOverviewGui overview;
    private final LanguageManager language;

    public OverviewSubCommand(EventOverviewGui overview, LanguageManager language) {
        this.overview = overview;
        this.language = language;
    }

    @Override public String name() { return "overview"; }
    @Override public String permission() { return PERMISSION; }

    @Override
    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal(name())
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(context -> {
                    if (context.getSource().getSender() instanceof Player player) {
                        overview.open(player, 0);
                    } else {
                        context.getSource().getSender().sendMessage(language.chat("gui.player-only"));
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
