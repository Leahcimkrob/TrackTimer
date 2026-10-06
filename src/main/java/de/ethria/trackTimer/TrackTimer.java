package de.ethria.trackTimer;

import de.ethria.trackTimer.command.TrackTimerCommand;
import de.ethria.trackTimer.database.DatabaseManager;
import de.ethria.trackTimer.heads.HeadDatabaseService;
import de.ethria.trackTimer.gui.EventOverviewGui;
import de.ethria.trackTimer.gui.EditorGuiContext;
import de.ethria.trackTimer.gui.EventEditorGui;
import de.ethria.trackTimer.gui.EventIconSwapGui;
import de.ethria.trackTimer.gui.EventTriggerGui;
import de.ethria.trackTimer.gui.TopTenGui;
import de.ethria.trackTimer.gui.RedstoneRaceSelectionGui;
import de.ethria.trackTimer.gui.PlayerDetailsGui;
import de.ethria.trackTimer.tools.StartTriggerTool;
import de.ethria.trackTimer.tools.EndTriggerTool;
import de.ethria.trackTimer.tools.RedstoneTriggerTool;
import de.ethria.trackTimer.tools.CheckpointTriggerTool;
import de.ethria.trackTimer.tools.RaceStatisticsHologramTool;
import de.ethria.trackTimer.tools.RaceStatisticsHologramDeleteTool;
import de.ethria.trackTimer.race.RaceStartListener;
import de.ethria.trackTimer.race.RaceCheckpointListener;
import de.ethria.trackTimer.race.RaceTriggerMonitor;
import de.ethria.trackTimer.race.RaceEndListener;
import de.ethria.trackTimer.race.RaceStatisticsEvaluator;
import de.ethria.trackTimer.race.RaceStatisticsHologramManager;
import de.ethria.trackTimer.race.RedstoneStartListener;
import de.ethria.trackTimer.language.LanguageManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;

public final class TrackTimer extends JavaPlugin {

    private DatabaseManager databaseManager;
    private LanguageManager languageManager;
    private HeadDatabaseService headDatabaseService;
    private EditorGuiContext editorGuiContext;
    private RaceStartListener raceStartListener;
    private RaceTriggerMonitor raceTriggerMonitor;
    private RaceStatisticsEvaluator raceStatisticsEvaluator;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        updateConfigWithNewDefaults();

        languageManager = new LanguageManager(this);
        languageManager.load();

        try {
            databaseManager = new DatabaseManager(this);
            databaseManager.initialize();
        } catch (SQLException | IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "Could not initialize the database.", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        headDatabaseService = new HeadDatabaseService(this);
        headDatabaseService.register();

        editorGuiContext = new EditorGuiContext(this, databaseManager, languageManager, headDatabaseService);
        EventOverviewGui eventOverviewGui = new EventOverviewGui(editorGuiContext);
        EventIconSwapGui eventIconSwapGui = new EventIconSwapGui(editorGuiContext);
        EventEditorGui eventEditorGui = new EventEditorGui(editorGuiContext, eventOverviewGui);
        StartTriggerTool startTriggerTool = new StartTriggerTool(editorGuiContext, eventEditorGui);
        EndTriggerTool endTriggerTool = new EndTriggerTool(editorGuiContext, eventEditorGui);
        RedstoneTriggerTool redstoneTriggerTool = new RedstoneTriggerTool(editorGuiContext, eventEditorGui);
        CheckpointTriggerTool checkpointTriggerTool = new CheckpointTriggerTool(editorGuiContext, eventEditorGui);
        EventTriggerGui eventTriggerGui = new EventTriggerGui(editorGuiContext, eventEditorGui,
                startTriggerTool, endTriggerTool, redstoneTriggerTool, checkpointTriggerTool);
        eventOverviewGui.setEventEditorGui(eventEditorGui);
        eventEditorGui.setIconSwapGui(eventIconSwapGui);
        eventEditorGui.setTriggerGui(eventTriggerGui);
        eventIconSwapGui.setEventEditorGui(eventEditorGui);
        getServer().getPluginManager().registerEvents(eventOverviewGui, this);
        getServer().getPluginManager().registerEvents(eventEditorGui, this);
        getServer().getPluginManager().registerEvents(eventIconSwapGui, this);
        getServer().getPluginManager().registerEvents(eventTriggerGui, this);
        getServer().getPluginManager().registerEvents(startTriggerTool, this);
        getServer().getPluginManager().registerEvents(endTriggerTool, this);
        getServer().getPluginManager().registerEvents(redstoneTriggerTool, this);
        getServer().getPluginManager().registerEvents(checkpointTriggerTool, this);
        raceStartListener = new RaceStartListener(this, databaseManager, languageManager);
        RedstoneStartListener redstoneStartListener = new RedstoneStartListener(this, databaseManager, languageManager);
        RaceCheckpointListener raceCheckpointListener = new RaceCheckpointListener(
                this, databaseManager, languageManager, raceStartListener);
        raceStatisticsEvaluator = new RaceStatisticsEvaluator(
                this, databaseManager);
        RaceStatisticsHologramManager hologramManager = new RaceStatisticsHologramManager(
                this, databaseManager, languageManager, raceStatisticsEvaluator);
        hologramManager.load();
        TopTenGui topTenGui = new TopTenGui(editorGuiContext, raceStatisticsEvaluator, eventOverviewGui);
        PlayerDetailsGui playerDetailsGui = new PlayerDetailsGui(editorGuiContext);
        topTenGui.setPlayerDetailsGui(playerDetailsGui);
        RaceStatisticsHologramTool hologramTool = null;
        RaceStatisticsHologramDeleteTool hologramDeleteTool = null;
        if (hologramManager.isAvailable()) {
            hologramTool = new RaceStatisticsHologramTool(editorGuiContext, hologramManager);
            getServer().getPluginManager().registerEvents(hologramTool, this);
            hologramDeleteTool = new RaceStatisticsHologramDeleteTool(editorGuiContext, hologramManager);
            getServer().getPluginManager().registerEvents(hologramDeleteTool, this);
        } else {
            getLogger().info("No hologram provider found; TrackTimer will run without the hologram feature.");
        }
        topTenGui.setHologramTool(hologramTool);
        topTenGui.setHologramDeleteTool(hologramDeleteTool);
        RedstoneRaceSelectionGui redstoneRaceSelectionGui = new RedstoneRaceSelectionGui(
                editorGuiContext, raceStatisticsEvaluator, eventOverviewGui, topTenGui);
        topTenGui.setRedstoneRaceSelectionGui(redstoneRaceSelectionGui);
        eventOverviewGui.setTopTenGui(topTenGui);
        eventOverviewGui.setRedstoneRaceSelectionGui(redstoneRaceSelectionGui);
        getServer().getPluginManager().registerEvents(topTenGui, this);
        getServer().getPluginManager().registerEvents(playerDetailsGui, this);
        getServer().getPluginManager().registerEvents(redstoneRaceSelectionGui, this);
        RaceEndListener raceEndListener = new RaceEndListener(
                this, databaseManager, languageManager, raceStartListener, hologramManager);
        raceTriggerMonitor = new RaceTriggerMonitor(this);
        raceTriggerMonitor.addHandler(raceStartListener::onRacePosition);
        raceTriggerMonitor.addHandler(raceCheckpointListener::onRacePosition);
        raceTriggerMonitor.addHandler(raceEndListener::onRacePosition);
        getServer().getPluginManager().registerEvents(raceStartListener, this);
        getServer().getPluginManager().registerEvents(redstoneStartListener, this);
        getServer().getPluginManager().registerEvents(raceTriggerMonitor, this);
        registerMainCommand(eventOverviewGui, raceStartListener, raceStatisticsEvaluator,
                hologramTool, hologramDeleteTool);
    }

    /**
     * Adds any new keys from the bundled config.yml to an existing config
     * file on disk without overwriting values the server owner already
     * configured. Without this, keys added in a plugin update (e.g.
     * "language", "command.aliases") would silently stay missing from the
     * file on disk, even though they still worked via in-memory defaults.
     * Also invoked by {@link de.ethria.trackTimer.command.ReloadSubCommand}.
     *
     * <p>Important: {@code copyDefaults(true)} only affects how the config
     * is serialized by {@link #saveConfig()}; it does not merge the
     * defaults into the in-memory config section. Callers that read values
     * with an explicit fallback (e.g. {@code getString(path, "fallback")})
     * bypass Bukkit's internal defaults entirely once the key is missing
     * from the base section, so the merged values must be reloaded from
     * disk after saving for them to become part of the base config.
     */
    public void updateConfigWithNewDefaults() {
        reloadConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        reloadConfig();
    }

    /**
     * Paper plugins (paper-plugin.yml) register commands through the
     * lifecycle "COMMANDS" event with a Brigadier command tree, since
     * plugin.yml command declarations and JavaPlugin#getCommand are not
     * supported for this plugin format.
     */
    private void registerMainCommand(EventOverviewGui eventOverviewGui, RaceStartListener raceStartListener,
                                     RaceStatisticsEvaluator raceStatisticsEvaluator,
                                     RaceStatisticsHologramTool hologramTool,
                                     RaceStatisticsHologramDeleteTool hologramDeleteTool) {
        TrackTimerCommand command = new TrackTimerCommand(this, databaseManager, languageManager,
                eventOverviewGui, raceStartListener, raceStatisticsEvaluator, hologramTool, hologramDeleteTool);
        List<String> aliases = getConfig().getStringList("command.aliases");

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        command.build("tracktimer"),
                        "Main command for TrackTimer.",
                        aliases
                )
        );
    }

    @Override
    public void onDisable() {
        if (raceTriggerMonitor != null) raceTriggerMonitor.shutdown();
        if (raceStartListener != null) raceStartListener.shutdown();
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    public HeadDatabaseService getHeadDatabaseService() {
        return headDatabaseService;
    }

    public void reloadEditorGuiConfig() {
        if (editorGuiContext != null) editorGuiContext.reload();
    }
}
