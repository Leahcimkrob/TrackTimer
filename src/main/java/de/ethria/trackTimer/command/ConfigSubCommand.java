package de.ethria.trackTimer.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import de.ethria.trackTimer.TrackTimer;
import de.ethria.trackTimer.gui.ConfigGui;
import de.ethria.trackTimer.language.LanguageManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;

/** Opens the configuration editor. */
public final class ConfigSubCommand implements SubCommand {
    private final TrackTimer plugin;
    private final LanguageManager language;
    public ConfigSubCommand(TrackTimer plugin, LanguageManager language) {
        this.plugin = plugin;
        this.language = language;
    }
    @Override public String name() { return "config"; }
    @Override public String permission() { return ConfigGui.PERMISSION; }
    @Override public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal(name()).requires(source -> source.getSender().hasPermission(permission()))
                .executes(context -> {
                    if (context.getSource().getSender() instanceof Player player) plugin.openConfigGui(player);
                    else context.getSource().getSender().sendMessage(language.chat("general.player-only"));
                    return Command.SINGLE_SUCCESS;
                }).build();
    }
}
