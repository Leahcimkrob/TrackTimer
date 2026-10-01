package de.ethria.trackTimer.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import de.ethria.trackTimer.TrackTimer;
import de.ethria.trackTimer.language.LanguageManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

import java.util.List;

/**
 * Builds the Brigadier command tree for {@code /tracktimer}. Paper plugins
 * (paper-plugin.yml) do not support legacy plugin.yml command declarations
 * or JavaPlugin#getCommand, so commands must be registered through the
 * Paper lifecycle "COMMANDS" event using this Brigadier-based builder.
 *
 * Each subcommand lives in its own {@link SubCommand} implementation;
 * this class only wires them together under the root command node.
 */
public final class TrackTimerCommand {

    private final TrackTimer plugin;
    private final LanguageManager language;

    public TrackTimerCommand(TrackTimer plugin, LanguageManager language) {
        this.plugin = plugin;
        this.language = language;
    }

    public LiteralCommandNode<CommandSourceStack> build(String label) {
        ReloadSubCommand reload = new ReloadSubCommand(plugin, language);
        HelpSubCommand help = new HelpSubCommand(language, label, List.of(reload));
        List<SubCommand> subCommands = List.of(help, reload);

        var root = Commands.literal(label)
                .executes(context -> {
                    help.execute(context.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                });

        for (SubCommand subCommand : subCommands) {
            root.then(subCommand.build());
        }

        return root.build();
    }
}

