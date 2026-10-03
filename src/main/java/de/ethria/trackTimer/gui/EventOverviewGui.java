package de.ethria.trackTimer.gui;

import de.ethria.trackTimer.database.DatabaseManager.Event;
import de.ethria.trackTimer.language.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** The paginated GUI that lists all registered events. */
public final class EventOverviewGui implements Listener {
    private static final int PAGE_SIZE = 45;
    private final EditorGuiContext context;
    private EventEditorGui eventEditorGui;
    private TopTenGui topTenGui;
    private RedstoneRaceSelectionGui redstoneRaceSelectionGui;

    public EventOverviewGui(EditorGuiContext context) {
        this.context = context;
    }

    public void setEventEditorGui(EventEditorGui eventEditorGui) {
        this.eventEditorGui = eventEditorGui;
    }

    public void setTopTenGui(TopTenGui topTenGui) {
        this.topTenGui = topTenGui;
    }

    public void setRedstoneRaceSelectionGui(RedstoneRaceSelectionGui redstoneRaceSelectionGui) {
        this.redstoneRaceSelectionGui = redstoneRaceSelectionGui;
    }

    public void open(Player player, int requestedPage) {
        try {
            List<Event> events = context.database.listEvents();
            int pages = Math.max(1, (events.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            int page = Math.max(0, Math.min(requestedPage, pages - 1));
            OverviewHolder holder = new OverviewHolder(page, events);
            int size = context.inventorySize("guis.event-overview.size", 54);
            Inventory inventory = Bukkit.createInventory(holder, size, context.language.gui("overview.title"));
            holder.inventory = inventory;
            int start = page * PAGE_SIZE;
            for (int index = start; index < Math.min(start + PAGE_SIZE, events.size()); index++) {
                Event event = events.get(index);
                ItemStack item = context.icon(event.icon());
                ItemMeta meta = item.getItemMeta();
                meta.displayName(context.language.gui("overview.event-item.name", LanguageManager.placeholders("event", event.name())));
                boolean german = context.language.getLocale().toLowerCase(java.util.Locale.ROOT).startsWith("de");
                String startModeLabel = "signal".equals(event.startMode())
                        ? (german ? "Redstone-Signal" : "Redstone signal")
                        : (german ? "Spieler" : "Player");
                List<net.kyori.adventure.text.Component> lore = new ArrayList<>(context.language.guiList(
                        "overview.event-item.lore", LanguageManager.placeholders("event", event.name(),
                                "laps", event.laps(), "start_mode", startModeLabel, "created", event.created())));
                if ("signal".equals(event.startMode())) {
                    lore.add(context.language.gui("overview.event-item.redstone-click"));
                    lore.add(context.language.gui("overview.event-item.redstone-standard-click"));
                } else {
                    lore.add(context.language.gui("overview.event-item.topten-click"));
                }
                if (player.hasPermission("tracktimer.command.editor")) {
                    lore.add(context.language.gui("overview.event-item.edit-click"));
                }
                meta.lore(lore);
                item.setItemMeta(meta);
                inventory.setItem(index - start, item);
            }
            if (events.isEmpty()) inventory.setItem(22, named(new ItemStack(Material.BARRIER), "overview.empty-item.name"));
            if (page > 0) inventory.setItem(context.slot(context.settings,
                    "guis.event-overview.button-bar.previous-page.slot", 45, size),
                    button("guis.event-overview.button-bar.previous-page", Material.ARROW, "buttons.previous-page"));
            inventory.setItem(context.slot(context.settings,
                    "guis.event-overview.button-bar.close.slot", 49, size),
                    button("guis.event-overview.button-bar.close", Material.BARRIER, "buttons.close"));
            if (page + 1 < pages) inventory.setItem(context.slot(context.settings,
                    "guis.event-overview.button-bar.next-page.slot", 53, size),
                    button("guis.event-overview.button-bar.next-page", Material.ARROW, "buttons.next-page"));
            player.openInventory(inventory);
        } catch (SQLException exception) {
            reportDatabaseError(player, exception);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof OverviewHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        int size = event.getView().getTopInventory().getSize();
        int previousPageSlot = context.slot(context.settings,
                "guis.event-overview.button-bar.previous-page.slot", 45, size);
        int closeSlot = context.slot(context.settings,
                "guis.event-overview.button-bar.close.slot", 49, size);
        int nextPageSlot = context.slot(context.settings,
                "guis.event-overview.button-bar.next-page.slot", 53, size);
        if (slot == previousPageSlot) open(player, holder.page - 1);
        else if (slot == nextPageSlot) open(player, holder.page + 1);
        else if (slot == closeSlot) player.closeInventory();
        else if (slot >= 0 && slot < PAGE_SIZE) {
            int index = holder.page * PAGE_SIZE + slot;
            if (index < holder.events.size()) {
                Event selected = holder.events.get(index);
                if (event.getClick().isRightClick()) {
                    if (player.hasPermission("tracktimer.command.editor") && eventEditorGui != null) {
                        eventEditorGui.open(player, selected, holder.page);
                    } else {
                        player.sendMessage(context.language.chat("general.no-permission"));
                    }
                } else if ("signal".equals(selected.startMode())
                        && event.getClick().isShiftClick() && event.getClick().isLeftClick()) {
                    if (topTenGui != null) topTenGui.openStandard(player, selected, holder.page);
                } else if ("signal".equals(selected.startMode())
                        && event.getClick() == ClickType.LEFT && redstoneRaceSelectionGui != null) {
                    redstoneRaceSelectionGui.open(player, selected, holder.page);
                } else if (topTenGui != null) {
                    topTenGui.openStandard(player, selected, holder.page);
                }
            }
        }
    }

    private ItemStack button(String configPath, Material fallback, String key) {
        return named(context.configuredIcon(context.settings, configPath, fallback), key + ".name");
    }

    private ItemStack named(ItemStack item, String key) {
        ItemMeta meta = item.getItemMeta();
        meta.displayName(context.language.gui(key));
        List<net.kyori.adventure.text.Component> lore = context.language.guiList(key.replace(".name", ".lore"));
        if (!lore.isEmpty()) meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void reportDatabaseError(Player player, SQLException exception) {
        context.plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not load events for the GUI.", exception);
        player.sendMessage(context.language.chat("event.list-failed"));
    }

    private static final class OverviewHolder implements InventoryHolder {
        private final int page;
        private final List<Event> events;
        private Inventory inventory;
        private OverviewHolder(int page, List<Event> events) {
            this.page = page;
            this.events = new ArrayList<>(events);
        }
        @Override public Inventory getInventory() { return inventory; }
    }
}
