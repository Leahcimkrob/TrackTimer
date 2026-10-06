package de.ethria.trackTimer.tools;

import de.ethria.trackTimer.gui.EditorGuiContext;
import de.ethria.trackTimer.race.RaceStatisticsHologramManager;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.sql.SQLException;
import java.util.logging.Level;

/** Gives a reusable tool that removes holograms by clicking inside their selected area. */
public final class RaceStatisticsHologramDeleteTool implements Listener {
    private final EditorGuiContext context;
    private final RaceStatisticsHologramManager holograms;
    private final NamespacedKey toolKey;

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
        var lore = context.language().guiList("buttons.hologram-delete.lore");
        if (!lore.isEmpty()) meta.lore(lore);
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        player.getInventory().addItem(item);
        player.sendMessage(context.language().chat("race-statistics.hologram-delete-tool-started"));
    }

    @EventHandler
    public void onBlockInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isTool(event.getItem())) return;
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        event.setCancelled(true);
        if (event.getClickedBlock() == null) return;
        try {
            int removed = holograms.deleteHologramsAt(event.getClickedBlock().getLocation());
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

    private boolean isTool(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(toolKey, PersistentDataType.BYTE);
    }
}
