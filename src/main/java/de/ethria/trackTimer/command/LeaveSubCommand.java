package de.ethria.trackTimer.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import de.ethria.trackTimer.language.LanguageManager;
import de.ethria.trackTimer.race.RaceStartListener;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.logging.Level;

/** {@code /tracktimer leave} - cancels all of the player's unfinished runs. */
public final class LeaveSubCommand implements SubCommand {
    private static final String PERMISSION = "tracktimer.command.leave";

    private final LanguageManager language;
    private final RaceStartListener raceStartListener;

    public LeaveSubCommand(LanguageManager language, RaceStartListener raceStartListener) {
        this.language = language;
        this.raceStartListener = raceStartListener;
    }

    @Override public String name() { return "leave"; }
    @Override public String permission() { return PERMISSION; }

    @Override
    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal(name())
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(context -> execute(context.getSource().getSender()))
                .build();
    }

    private int execute(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(language.chat("general.player-only"));
            return Command.SINGLE_SUCCESS;
        }
        try {
            int cancelled = raceStartListener.cancelPlayerRaces(player);
            if (cancelled == 0) sender.sendMessage(language.chat("race.not-running"));
            else sender.sendMessage(language.chat("race.cancelled"));
        } catch (SQLException exception) {
            player.getServer().getLogger().log(Level.SEVERE,
                    "Could not cancel unfinished races for " + player.getName() + ".", exception);
            player.sendMessage(language.chat("database.error",
                    LanguageManager.placeholders("message", exception.getMessage())));
        }
        return Command.SINGLE_SUCCESS;
    }
}
