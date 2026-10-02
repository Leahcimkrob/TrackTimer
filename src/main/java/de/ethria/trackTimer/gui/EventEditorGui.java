package de.ethria.trackTimer.gui;

import de.ethria.trackTimer.database.DatabaseManager;
import de.ethria.trackTimer.database.DatabaseManager.Event;
import de.ethria.trackTimer.language.LanguageManager;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;

/** The per-event editing GUI. */
public final class EventEditorGui implements Listener {
    private final EditorGuiContext context;
    private final EventOverviewGui overviewGui;
    private EventIconSwapGui iconSwapGui;
    private EventTriggerGui triggerGui;

    public EventEditorGui(EditorGuiContext context, EventOverviewGui overviewGui) {
        this.context = context;
        this.overviewGui = overviewGui;
    }

    public void setIconSwapGui(EventIconSwapGui iconSwapGui) {
        this.iconSwapGui = iconSwapGui;
    }

    public void setTriggerGui(EventTriggerGui triggerGui) {
        this.triggerGui = triggerGui;
    }

    public void open(Player player, Event event, int overviewPage) {
        int size = context.inventorySize("guis.event-editor.size", 54);
        EditorHolder holder = new EditorHolder(event, overviewPage);
        Inventory inventory = Bukkit.createInventory(holder, size,
                context.language.gui("event-editor.title", LanguageManager.placeholders("event", event.name())));
        holder.inventory = inventory;

        ItemStack name = context.configuredItem("guis.event-editor.items.event-name", Material.CRAFTING_TABLE);
        ItemMeta nameMeta = name.getItemMeta();
        nameMeta.displayName(context.language.gui("event-editor.event-name.name",
                LanguageManager.placeholders("event", event.name())));
        nameMeta.lore(context.language.guiList("event-editor.event-name.lore", LanguageManager.placeholders("event", event.name())));
        name.setItemMeta(nameMeta);
        inventory.setItem(slot("items.event-name.slot", 13, size), name);

        ItemStack laps = context.configuredItem("guis.event-editor.items.laps", Material.STRUCTURE_VOID);
        laps.setAmount(Math.max(1, Math.min(64, event.laps())));
        ItemMeta lapsMeta = laps.getItemMeta();
        lapsMeta.displayName(context.language.gui("event-editor.laps.name"));
        lapsMeta.lore(context.language.guiList("event-editor.laps.lore", LanguageManager.placeholders("laps", event.laps())));
        laps.setItemMeta(lapsMeta);
        inventory.setItem(slot("items.laps.slot", 21, size), laps);

        ItemStack startMode = context.configuredIcon("guis.event-editor.items.start-mode", Material.REDSTONE);
        ItemMeta startModeMeta = startMode.getItemMeta();
        startModeMeta.displayName(context.language.gui("event-editor.start-mode.name"));
        boolean german = context.language.getLocale().toLowerCase(java.util.Locale.ROOT).startsWith("de");
        String modeLabel = "signal".equals(event.startMode())
                ? (german ? "Redstone-Signal" : "Redstone signal")
                : (german ? "Spieler" : "Player");
        startModeMeta.lore(context.language.guiList("event-editor.start-mode.lore",
                LanguageManager.placeholders("start_mode", modeLabel)));
        startMode.setItemMeta(startModeMeta);
        inventory.setItem(slot("items.start-mode.slot", 23, size), startMode);

        inventory.setItem(slot("items.change-icon.slot", 29, size), named(
                context.configuredItem("guis.event-editor.items.change-icon", Material.CHEST), "event-editor.change-icon"));
        inventory.setItem(slot("items.delete.slot", 31, size), named(
                context.configuredItem("guis.event-editor.items.delete", Material.BARRIER), "event-editor.delete"));
        inventory.setItem(slot("items.trigger-editor.slot", 33, size), named(
                context.configuredIcon("guis.event-editor.items.trigger-editor", Material.REDSTONE_LAMP), "event-editor.trigger-editor"));

        ItemStack bar = new ItemStack(context.material(context.settings.getString("guis.event-editor.button-bar.material"), Material.GRAY_STAINED_GLASS_PANE));
        ItemMeta barMeta = bar.getItemMeta();
        barMeta.displayName(net.kyori.adventure.text.Component.empty());
        bar.setItemMeta(barMeta);
        for (int buttonSlot = size - 9; buttonSlot < size; buttonSlot++) inventory.setItem(buttonSlot, bar.clone());
        inventory.setItem(slot("button-bar.back.slot", size - 5, size), button(
                context.material(context.settings.getString("guis.event-editor.button-bar.back.material"), Material.ARROW), "buttons.back"));
        player.openInventory(inventory);
    }

    public void reopen(Player player, long eventId, int overviewPage) {
        try {
            for (Event event : context.database.listEvents()) {
                if (event.id() == eventId) {
                    open(player, event, overviewPage);
                    return;
                }
            }
            overviewGui.open(player, overviewPage);
        } catch (SQLException exception) {
            reportDatabaseError(player, exception);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof EditorHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) return;
        int size = context.inventorySize("guis.event-editor.size", 54);
        int rawSlot = event.getRawSlot();
        Event selected = holder.event;
        if (rawSlot == slot("items.event-name.slot", 13, size)) {
            if (event.getClick() == ClickType.LEFT) openNameDialog(player, selected, holder.overviewPage);
        } else if (rawSlot == slot("items.laps.slot", 21, size)) {
            int laps = selected.laps();
            if (event.getClick().isRightClick()) laps = Math.min(9999, laps + 1);
            else if (event.getClick().isLeftClick()) laps = Math.max(1, laps - 1);
            else return;
            try {
                context.database.updateEventLaps(selected.id(), laps);
                reopen(player, selected.id(), holder.overviewPage);
            } catch (SQLException exception) {
                reportDatabaseError(player, exception);
            }
        } else if (rawSlot == slot("items.change-icon.slot", 29, size)) {
            if (iconSwapGui != null) iconSwapGui.open(player, selected, holder.overviewPage);
        } else if (rawSlot == slot("items.start-mode.slot", 23, size)) {
            if (event.getClick() == ClickType.LEFT) {
                String nextMode = "player".equals(selected.startMode()) ? "signal" : "player";
                try {
                    context.database.updateEventStartMode(selected.id(), nextMode);
                    reopen(player, selected.id(), holder.overviewPage);
                } catch (SQLException exception) {
                    reportDatabaseError(player, exception);
                }
            }
        } else if (rawSlot == slot("items.delete.slot", 31, size)) {
            if (event.getClick().isShiftClick() && event.getClick().isLeftClick()) {
                deleteEvent(player, selected, holder.overviewPage);
            }
        } else if (rawSlot == slot("items.trigger-editor.slot", 33, size)) {
            if (triggerGui != null) triggerGui.open(player, selected, holder.overviewPage);
        } else if (rawSlot == slot("button-bar.back.slot", size - 5, size)) {
            overviewGui.open(player, holder.overviewPage);
        }
    }

    private void openNameDialog(Player player, Event event, int overviewPage) {
        Component title = context.language.gui("event-editor.name-dialog.title",
                LanguageManager.placeholders("event", event.name()));
        Component inputLabel = context.language.gui("event-editor.name-dialog.input-label");
        ItemStack eventIcon = context.icon(event.icon());

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(title)
                        .body(List.of(DialogBody.item(eventIcon, null, true, true, 32, 32)))
                        .inputs(List.of(DialogInput.text("event_name", 300, inputLabel, true,
                                event.name(), 128, null)))
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.create(
                                context.language.gui("event-editor.name-dialog.save"),
                                null,
                                150,
                                DialogAction.customClick((response, audience) -> {
                                    if (!(audience instanceof Player clicker)) return;
                                    String newName = response.getText("event_name");
                                    saveEventName(clicker, event.id(), overviewPage, newName);
                                }, ClickCallback.Options.builder().uses(1).build())
                        ),
                        ActionButton.create(context.language.gui("event-editor.name-dialog.cancel"), null, 150, null)
                )));
        player.showDialog(dialog);
    }

    private void saveEventName(Player player, long eventId, int overviewPage, String submittedName) {
        String newName = submittedName == null ? "" : submittedName.trim();
        if (!DatabaseManager.isValidEventName(newName)) {
            player.sendMessage(context.language.chat("event.invalid-name"));
            reopen(player, eventId, overviewPage);
            return;
        }
        try {
            if (!context.database.updateEventName(eventId, newName)) {
                player.sendMessage(context.language.chat("event.already-exists",
                        LanguageManager.placeholders("event", newName)));
                reopen(player, eventId, overviewPage);
                return;
            }
            player.sendMessage(context.language.chat("event.name-updated",
                    LanguageManager.placeholders("event", newName)));
            reopen(player, eventId, overviewPage);
        } catch (SQLException exception) {
            reportDatabaseError(player, exception);
            reopen(player, eventId, overviewPage);
        }
    }

    private void deleteEvent(Player player, Event event, int overviewPage) {
        try {
            context.database.deleteEvent(event.id());
            player.sendMessage(context.language.chat("event.deleted", LanguageManager.placeholders("event", event.name())));
            overviewGui.open(player, overviewPage);
        } catch (SQLException exception) {
            reportDatabaseError(player, exception);
        }
    }

    private ItemStack button(Material material, String key) {
        return named(new ItemStack(material), key);
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
        return context.slot("guis.event-editor." + suffix, fallback, size);
    }

    private void reportDatabaseError(Player player, SQLException exception) {
        context.plugin.getLogger().log(Level.SEVERE, "Could not update the event editor.", exception);
        player.sendMessage(context.language.chat("event.list-failed"));
    }

    private static final class EditorHolder implements InventoryHolder {
        private final Event event;
        private final int overviewPage;
        private Inventory inventory;
        private EditorHolder(Event event, int overviewPage) {
            this.event = event;
            this.overviewPage = overviewPage;
        }
        @Override public Inventory getInventory() { return inventory; }
    }
}
