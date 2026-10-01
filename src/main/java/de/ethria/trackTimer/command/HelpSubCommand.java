package de.ethria.trackTimer.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import de.ethria.trackTimer.language.LanguageManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * {@code /tracktimer help} - lists every subcommand the sender has
 * permission to use, including itself.
 */
public final class HelpSubCommand implements SubCommand {

    private static final String PERMISSION = "tracktimer.command.help";

    private final LanguageManager language;
    private final String label;
    private final List<SubCommand> otherSubCommands;

    /**
     * @param otherSubCommands every other subcommand of /tracktimer (not
     *                         including this help command itself)
     */
    public HelpSubCommand(LanguageManager language, String label, List<SubCommand> otherSubCommands) {
        this.language = language;
        this.label = label;
        this.otherSubCommands = otherSubCommands;
    }

    @Override
    public String name() {
        return "help";
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

    public void execute(CommandSender sender) {
        TagResolver command = LanguageManager.placeholders("command", label);
        sender.sendMessage(language.chat("help.header"));
        sender.sendMessage(language.chat("help." + name(), command));
        for (SubCommand subCommand : otherSubCommands) {
            if (sender.hasPermission(subCommand.permission())) {
                sender.sendMessage(language.chat("help." + subCommand.name(), command));
            }
        }
    }
}
