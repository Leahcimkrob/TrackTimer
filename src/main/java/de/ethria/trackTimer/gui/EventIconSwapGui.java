package de.ethria.trackTimer.gui;

import de.ethria.trackTimer.database.DatabaseManager.Event;
import de.ethria.trackTimer.language.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;

/** The one-slot inventory used to replace an event's icon item. */
public final class EventIconSwapGui implements Listener {
    private final EditorGuiContext context;
    private EventEditorGui eventEditorGui;

    public EventIconSwapGui(EditorGuiContext context) {
        this.context = context;
    }

    public void setEventEditorGui(EventEditorGui eventEditorGui) {
        this.eventEditorGui = eventEditorGui;
    }

    public void open(Player player, Event event, int overviewPage) {
        int size = context.inventorySize("guis.event-icon-swap.size", 36);
        SwapHolder holder = new SwapHolder(event, overviewPage);
        Inventory inventory = Bukkit.createInventory(holder, size,
                context.language.gui("event-editor.change-icon.title", LanguageManager.placeholders("event", event.name())));
        holder.inventory = inventory;
        Material filler = context.material(context.settings.getString("guis.event-icon-swap.filler-material"), Material.RED_STAINED_GLASS_PANE);
        ItemStack pane = new ItemStack(filler);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.empty());
        pane.setItemMeta(meta);
        for (int slot = 0; slot < size; slot++) inventory.setItem(slot, pane.clone());
        Material barMaterial = context.material(context.settings.getString("guis.event-icon-swap.button-bar.material"),
                Material.GRAY_STAINED_GLASS_PANE);
        ItemStack bar = new ItemStack(barMaterial);
        ItemMeta barMeta = bar.getItemMeta();
        barMeta.displayName(net.kyori.adventure.text.Component.empty());
        bar.setItemMeta(barMeta);
        for (int slot = size - 9; slot < size; slot++) inventory.setItem(slot, bar.clone());
        int backSlot = context.slot("guis.event-icon-swap.button-bar.back.slot", size - 5, size);
        inventory.setItem(backSlot, backButton());
        int center = context.slot("guis.event-icon-swap.center-slot", 13, size);
        inventory.setItem(center, context.icon(event.icon()));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SwapHolder)) return;
        int size = context.inventorySize("guis.event-icon-swap.size", 36);
        int center = context.slot("guis.event-icon-swap.center-slot", 13, size);
        boolean top = event.getClickedInventory() == event.getView().getTopInventory();
        if (top) {
            int backSlot = context.slot("guis.event-icon-swap.button-bar.back.slot", size - 5, size);
            if (event.getRawSlot() == backSlot && event.getWhoClicked() instanceof Player player) {
                event.setCancelled(true);
                player.closeInventory();
            } else if (event.getRawSlot() != center) {
                event.setCancelled(true);
            }
        } else if (event.getClick().isShiftClick()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SwapHolder)) return;
        int center = context.slot("guis.event-icon-swap.center-slot", 13,
                context.inventorySize("guis.event-icon-swap.size", 36));
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(rawSlot -> rawSlot < topSize && rawSlot != center)) event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SwapHolder holder)) return;
        int center = context.slot("guis.event-icon-swap.center-slot", 13,
                context.inventorySize("guis.event-icon-swap.size", 36));
        ItemStack replacement = event.getView().getTopInventory().getItem(center);
        if (!(event.getPlayer() instanceof Player player)) return;
        if (replacement == null || replacement.getType().isAir()) {
            returnToEditor(player, holder);
            return;
        }
        try {
            context.database.updateEventIcon(holder.event.id(), context.serializeIcon(replacement));
            returnToEditor(player, holder);
        } catch (SQLException exception) {
            context.plugin.getLogger().log(Level.SEVERE, "Could not update event icon.", exception);
            player.sendMessage(context.language.chat("event.list-failed"));
            returnToEditor(player, holder);
        }
    }

    private void returnToEditor(Player player, SwapHolder holder) {
        if (eventEditorGui != null) {
            context.plugin.getServer().getScheduler().runTask(context.plugin,
                    () -> eventEditorGui.reopen(player, holder.event.id(), holder.overviewPage));
        }
    }

    private ItemStack backButton() {
        ItemStack item = context.configuredIcon("guis.event-icon-swap.button-bar.back", Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(context.language.gui("buttons.back.name"));
        item.setItemMeta(meta);
        return item;
    }

    private static final class SwapHolder implements InventoryHolder {
        private final Event event;
        private final int overviewPage;
        private Inventory inventory;
        private SwapHolder(Event event, int overviewPage) {
            this.event = event;
            this.overviewPage = overviewPage;
        }
        @Override public Inventory getInventory() { return inventory; }
    }
}
