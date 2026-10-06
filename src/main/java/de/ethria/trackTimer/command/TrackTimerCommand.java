package de.ethria.trackTimer.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import de.ethria.trackTimer.TrackTimer;
import de.ethria.trackTimer.database.DatabaseManager;
import de.ethria.trackTimer.language.LanguageManager;
import de.ethria.trackTimer.gui.EventOverviewGui;
import de.ethria.trackTimer.race.RaceStartListener;
import de.ethria.trackTimer.race.RaceStatisticsEvaluator;
import de.ethria.trackTimer.tools.RaceStatisticsHologramDeleteTool;
import de.ethria.trackTimer.tools.RaceStatisticsHologramTool;
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
    private final DatabaseManager database;
    private final LanguageManager language;
    private final EventOverviewGui eventOverviewGui;
    private final RaceStartListener raceStartListener;
    private final RaceStatisticsEvaluator raceStatisticsEvaluator;
    private final RaceStatisticsHologramTool hologramTool;
    private final RaceStatisticsHologramDeleteTool hologramDeleteTool;

    public TrackTimerCommand(TrackTimer plugin, DatabaseManager database, LanguageManager language,
                             EventOverviewGui eventOverviewGui, RaceStartListener raceStartListener,
                             RaceStatisticsEvaluator raceStatisticsEvaluator,
                             RaceStatisticsHologramTool hologramTool,
                             RaceStatisticsHologramDeleteTool hologramDeleteTool) {
        this.plugin = plugin;
        this.database = database;
        this.language = language;
        this.eventOverviewGui = eventOverviewGui;
        this.raceStartListener = raceStartListener;
        this.raceStatisticsEvaluator = raceStatisticsEvaluator;
        this.hologramTool = hologramTool;
        this.hologramDeleteTool = hologramDeleteTool;
    }

    public LiteralCommandNode<CommandSourceStack> build(String label) {
        ReloadSubCommand reload = new ReloadSubCommand(plugin, language);
        CreateSubCommand create = new CreateSubCommand(plugin, database, language);
        DeleteSubCommand delete = new DeleteSubCommand(plugin, database, language);
        ResetSubCommand reset = new ResetSubCommand(plugin, database, language);
        EditorSubCommand editor = new EditorSubCommand(eventOverviewGui, language);
        OverviewSubCommand overview = new OverviewSubCommand(eventOverviewGui, language);
        ConvertSubCommand convert = new ConvertSubCommand(database, language);
        LeaveSubCommand leave = new LeaveSubCommand(language, raceStartListener);
        TopTenSubCommand topTen = new TopTenSubCommand(plugin, database, language, raceStatisticsEvaluator);
        HologramSubCommand hologram = new HologramSubCommand(plugin, database, language,
                raceStatisticsEvaluator, hologramTool, hologramDeleteTool);
        HelpSubCommand help = new HelpSubCommand(language, label,
                List.of(reload, create, delete, reset, editor, overview, convert, leave, topTen, hologram));
        List<SubCommand> subCommands = List.of(help, reload, create, delete, reset, editor, overview, convert, leave,
                topTen, hologram);

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
