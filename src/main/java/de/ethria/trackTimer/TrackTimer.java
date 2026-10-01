package de.ethria.trackTimer;

import de.ethria.trackTimer.command.TrackTimerCommand;
import de.ethria.trackTimer.database.DatabaseManager;
import de.ethria.trackTimer.language.LanguageManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;

public final class TrackTimer extends JavaPlugin {

    private DatabaseManager databaseManager;
    private LanguageManager languageManager;

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

        registerMainCommand();
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
    private void registerMainCommand() {
        TrackTimerCommand command = new TrackTimerCommand(this, languageManager);
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
        if (databaseManager != null) {
            databaseManager.close();
        }
    }
}

