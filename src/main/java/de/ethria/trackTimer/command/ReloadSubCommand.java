package de.ethria.trackTimer.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import de.ethria.trackTimer.TrackTimer;
import de.ethria.trackTimer.language.LanguageManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;

/**
 * {@code /tracktimer reload} - reloads config.yml and the language files.
 */
public final class ReloadSubCommand implements SubCommand {

    private static final String PERMISSION = "tracktimer.command.reload";

    private final TrackTimer plugin;
    private final LanguageManager language;

    public ReloadSubCommand(TrackTimer plugin, LanguageManager language) {
        this.plugin = plugin;
        this.language = language;
    }

    @Override
    public String name() {
        return "reload";
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
                    execute(context.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    private void execute(CommandSender sender) {
        plugin.updateConfigWithNewDefaults();
        language.load();
        plugin.reloadEditorGuiConfig();
        sender.sendMessage(language.chat("general.reload"));
    }
}

