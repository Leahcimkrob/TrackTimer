package de.ethria.trackTimer.heads;

import me.arcaniax.hdb.api.DatabaseLoadEvent;
import me.arcaniax.hdb.api.HeadDatabaseAPI;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;
import java.util.logging.Level;

/**
 * Wraps the optional HeadDatabase plugin (https://www.spigotmc.org/resources/14280/)
 * so GUIs can use custom heads by ID (e.g. a checkered flag for a finish
 * line) when it is installed, and fall back to plain player heads when it
 * is not. TrackTimer must keep working if HeadDatabase is absent, since it
 * is declared as an optional dependency in paper-plugin.yml.
 */
public final class HeadDatabaseService implements Listener {

    private final JavaPlugin plugin;
    private HeadDatabaseAPI api;
    private volatile boolean ready;

    public HeadDatabaseService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Registers the listener that waits for HeadDatabase to finish loading
     * its head cache. Safe to call even if HeadDatabase is not installed.
     */
    public void register() {
        if (plugin.getServer().getPluginManager().getPlugin("HeadDatabase") == null) {
            plugin.getLogger().info("HeadDatabase not found - custom GUI heads are disabled, using player heads instead.");
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onDatabaseLoad(DatabaseLoadEvent event) {
        try {
            api = new HeadDatabaseAPI();
            ready = true;
            plugin.getLogger().info("HeadDatabase detected - custom GUI heads are enabled.");
        } catch (NoClassDefFoundError | Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Could not initialize the HeadDatabase API.", exception);
        }
    }

    public boolean isAvailable() {
        return ready && api != null;
    }

    /**
     * Resolves a HeadDatabase head by its ID (as seen in-game via
     * {@code /hdb}, e.g. "7129"). Returns empty when HeadDatabase is not
     * installed/ready or the ID does not exist, so callers can fall back
     * to another icon (e.g. {@link #playerHead(OfflinePlayer)}).
     */
    public Optional<ItemStack> head(String id) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        try {
            ItemStack item = api.getItemHead(id);
            return Optional.ofNullable(item);
        } catch (NullPointerException exception) {
            plugin.getLogger().warning("Unknown HeadDatabase head ID: " + id);
            return Optional.empty();
        }
    }

    /**
     * Builds a plain player-skin head for the given player, usable as a
     * fallback GUI icon (e.g. a leaderboard entry) regardless of whether
     * HeadDatabase is installed.
     */
    public ItemStack playerHead(OfflinePlayer player) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (item.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(player);
            item.setItemMeta(meta);
        }
        return item;
    }

    public ItemStack playerHead(Player player) {
        return playerHead((OfflinePlayer) player);
    }
}
