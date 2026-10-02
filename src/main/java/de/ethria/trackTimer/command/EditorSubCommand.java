package de.ethria.trackTimer.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import de.ethria.trackTimer.gui.EventOverviewGui;
import de.ethria.trackTimer.language.LanguageManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;

public final class EditorSubCommand implements SubCommand {
    private static final String PERMISSION = "tracktimer.command.editor";
    private final EventOverviewGui gui;
    private final LanguageManager language;

    public EditorSubCommand(EventOverviewGui gui, LanguageManager language) {
        this.gui = gui;
        this.language = language;
    }

    @Override public String name() { return "editor"; }
    @Override public String permission() { return PERMISSION; }

    @Override
    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal(name())
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(context -> {
                    if (context.getSource().getSender() instanceof Player player) {
                        gui.open(player, 0);
                    } else {
                        context.getSource().getSender().sendMessage(language.chat("gui.player-only"));
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
