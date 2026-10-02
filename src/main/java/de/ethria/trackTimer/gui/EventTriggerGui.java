package de.ethria.trackTimer.gui;

import de.ethria.trackTimer.database.DatabaseManager.Event;
import de.ethria.trackTimer.database.DatabaseManager.EventTrigger;
import de.ethria.trackTimer.language.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;

/** Displays all configured start, checkpoint, and finish triggers for an event. */
public final class EventTriggerGui implements Listener {
    private final EditorGuiContext context;
    private final EventEditorGui eventEditorGui;

    public EventTriggerGui(EditorGuiContext context, EventEditorGui eventEditorGui) {
        this.context = context;
        this.eventEditorGui = eventEditorGui;
    }

    public void open(Player player, Event event, int overviewPage) {
        int size = context.inventorySize(context.triggerSettings, "guis.trigger-editor.size", 54, "trigger.yml");
        TriggerHolder holder = new TriggerHolder(event.id(), overviewPage);
        Inventory inventory = Bukkit.createInventory(holder, size, context.language.gui("trigger-editor.title",
                LanguageManager.placeholders("event", event.name())));
        holder.inventory = inventory;

        try {
            List<EventTrigger> triggers = context.database.listEventTriggerDetails(event.id());
            int contentSlots = size - 9;
            for (int index = 0; index < Math.min(triggers.size(), contentSlots); index++) {
                EventTrigger trigger = triggers.get(index);
                ItemStack item = triggerItem(trigger);
                inventory.setItem(index, item);
            }
        } catch (SQLException exception) {
            context.plugin.getLogger().log(Level.SEVERE, "Could not load event triggers for the GUI.", exception);
            player.sendMessage(context.language.chat("event.list-failed"));
        }

        ItemStack bar = new ItemStack(context.material(
                context.triggerSettings.getString("guis.trigger-editor.button-bar.material"), Material.GRAY_STAINED_GLASS_PANE));
        ItemMeta barMeta = bar.getItemMeta();
        barMeta.displayName(net.kyori.adventure.text.Component.empty());
        bar.setItemMeta(barMeta);
        for (int buttonSlot = size - 9; buttonSlot < size; buttonSlot++) inventory.setItem(buttonSlot, bar.clone());

        inventory.setItem(slot("items.add-trigger.slot", 47, size), configuredNamed(
                "guis.trigger-editor.items.add-trigger", Material.PLAYER_HEAD, "trigger-editor.add-trigger"));
        inventory.setItem(slot("button-bar.back.slot", size - 5, size), named(
                new ItemStack(context.material(context.triggerSettings.getString("guis.trigger-editor.button-bar.back.material"), Material.ARROW)),
                "buttons.back"));
        player.openInventory(inventory);
    }

    private ItemStack triggerItem(EventTrigger trigger) {
        String type = trigger.type().toLowerCase(java.util.Locale.ROOT);
        String key;
        String iconPath;
        if ("start".equals(type)) {
            key = "trigger-editor.start";
            iconPath = "guis.trigger-editor.items.start";
        } else if ("checkpoint".equals(type)) {
            key = "trigger-editor.checkpoint";
            iconPath = "guis.trigger-editor.items.checkpoint";
        } else if ("end".equals(type)) {
            key = "trigger-editor.end";
            iconPath = "guis.trigger-editor.items.end";
        } else {
            key = "trigger-editor.unknown";
            iconPath = "guis.trigger-editor.items.checkpoint";
        }

        ItemStack item = context.configuredIcon(context.triggerSettings, iconPath, Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (trigger.checkpointOrder() != null) {
            meta.displayName(context.language.gui(key + ".name",
                    LanguageManager.placeholders("number", trigger.checkpointOrder())));
        } else {
            meta.displayName(context.language.gui(key + ".name"));
        }
        meta.lore(context.language.guiList(key + ".lore", LanguageManager.placeholders(
                "server", trigger.server(), "world", trigger.world(), "x", trigger.x(), "y", trigger.y(),
                "z", trigger.z(), "block", trigger.blockType())));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack configuredNamed(String configPath, Material fallback, String languageKey) {
        return named(context.configuredIcon(context.triggerSettings, configPath, fallback), languageKey);
    }

    private ItemStack named(ItemStack item, String key) {
        ItemMeta meta = item.getItemMeta();
        meta.displayName(context.language.gui(key + ".name"));
        List<net.kyori.adventure.text.Component> lore = context.language.guiList(key + ".lore");
        if (!lore.isEmpty()) meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private int slot(String suffix, int fallback, int size) {
        return context.slot(context.triggerSettings, "guis.trigger-editor." + suffix, fallback, size);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof TriggerHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) return;
        int size = context.inventorySize(context.triggerSettings, "guis.trigger-editor.size", 54, "trigger.yml");
        if (event.getRawSlot() == slot("button-bar.back.slot", size - 5, size)) {
            eventEditorGui.reopen(player, holder.eventId, holder.overviewPage);
        }
        // Trigger creation and interaction behavior will be implemented later.
    }

    private static final class TriggerHolder implements InventoryHolder {
        private final long eventId;
        private final int overviewPage;
        private Inventory inventory;

        private TriggerHolder(long eventId, int overviewPage) {
            this.eventId = eventId;
            this.overviewPage = overviewPage;
        }

        @Override public Inventory getInventory() { return inventory; }
    }
}
