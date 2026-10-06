package de.ethria.trackTimer.tools;

import de.ethria.trackTimer.gui.EditorGuiContext;
import de.ethria.trackTimer.race.RaceStatisticsHologramManager;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** Gives a reusable tool that removes holograms by clicking inside their selected area. */
public final class RaceStatisticsHologramDeleteTool implements Listener {
    private final EditorGuiContext context;
    private final RaceStatisticsHologramManager holograms;
    private final NamespacedKey toolKey;
    private final Map<UUID, BukkitTask> expiryTasks = new HashMap<>();

    public RaceStatisticsHologramDeleteTool(EditorGuiContext context, RaceStatisticsHologramManager holograms) {
        this.context = context;
        this.holograms = holograms;
        this.toolKey = new NamespacedKey(context.plugin(), "race_statistics_hologram_delete_tool");
    }

    public void giveTool(org.bukkit.entity.Player player) {
        if (!holograms.isAvailable()) {
            player.sendMessage(context.language().chat("race-statistics.hologram-plugin-missing"));
            return;
        }
        ItemStack item = context.configuredIcon(context.topTenSettings(),
                "guis.topten.button-bar.hologram-delete", Material.STICK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(context.language().gui("buttons.hologram-delete.name"));
        long timeoutSeconds = timeoutSeconds();
        var lore = context.language().guiList("buttons.hologram-delete.lore",
                de.ethria.trackTimer.language.LanguageManager.placeholders("seconds", timeoutSeconds));
        if (!lore.isEmpty()) meta.lore(lore);
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        removeTools(player);
        player.getInventory().addItem(item);
        refreshExpiry(player);
        player.sendMessage(context.language().chat("race-statistics.hologram-delete-tool-started"));
    }

    @EventHandler
    public void onBlockInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isTool(event.getItem())) return;
        Action action = event.getAction();
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            removeTools(event.getPlayer());
            cancelExpiry(event.getPlayer().getUniqueId());
            return;
        }
        if (action != Action.LEFT_CLICK_BLOCK) return;
        event.setCancelled(true);
        refreshExpiry(event.getPlayer());
        if (event.getClickedBlock() == null) return;
        try {
            int removed = holograms.deleteHologramAt(event.getClickedBlock().getLocation(),
                    event.getPlayer().getLocation());
            if (removed == 0) {
                event.getPlayer().sendMessage(context.language().chat("race-statistics.hologram-delete-not-found"));
            } else {
                event.getPlayer().sendMessage(context.language().chat("race-statistics.hologram-deleted",
                        de.ethria.trackTimer.language.LanguageManager.placeholders("count", removed)));
            }
        } catch (SQLException exception) {
            context.plugin().getLogger().log(Level.SEVERE, "Could not delete a race statistics hologram.", exception);
            event.getPlayer().sendMessage(context.language().chat("race-statistics.command-failed"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancelExpiry(event.getPlayer().getUniqueId());
    }

    private void refreshExpiry(org.bukkit.entity.Player player) {
        cancelExpiry(player.getUniqueId());
        BukkitTask task = org.bukkit.Bukkit.getScheduler().runTaskLater(context.plugin(), () -> {
            expiryTasks.remove(player.getUniqueId());
            removeTools(player);
        }, timeoutSeconds() * 20L);
        expiryTasks.put(player.getUniqueId(), task);
    }

    private long timeoutSeconds() {
        return Math.max(1, Math.min(86_400,
                context.plugin().getConfig().getLong("race-statistics.hologram.delete-tool-timeout-seconds", 10)));
    }

    private void cancelExpiry(UUID playerId) {
        BukkitTask task = expiryTasks.remove(playerId);
        if (task != null) task.cancel();
    }

    private void removeTools(org.bukkit.entity.Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (isTool(player.getInventory().getItem(slot))) player.getInventory().setItem(slot, null);
        }
    }

    private boolean isTool(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(toolKey, PersistentDataType.BYTE);
    }
}
