package de.ethria.trackTimer.gui;

import de.ethria.trackTimer.database.DatabaseManager;
import de.ethria.trackTimer.database.DatabaseManager.Event;
import de.ethria.trackTimer.language.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.sql.SQLException;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** The per-event editing GUI. */
public final class EventEditorGui implements Listener {
    private final EditorGuiContext context;
    private final EventOverviewGui overviewGui;
    private EventIconSwapGui iconSwapGui;
    private EventTriggerGui triggerGui;
    private final Map<UUID, NameEditSession> nameEditSessions = new HashMap<>();

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

    void reopen(Player player, long eventId, int overviewPage) {
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
            if (event.getClick() == ClickType.LEFT) openNameSign(player, selected, holder.overviewPage);
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

    private void openNameSign(Player player, Event event, int overviewPage) {
        cancelNameEdit(player.getUniqueId());
        Block signBlock = findTemporarySignLocation(player);
        if (signBlock == null) {
            player.sendMessage(context.language.chat("event.name-sign-unavailable"));
            return;
        }

        BlockData originalData = signBlock.getBlockData().clone();
        NameEditSession session = new NameEditSession(event.id(), overviewPage, signBlock.getLocation(), originalData);
        nameEditSessions.put(player.getUniqueId(), session);
        signBlock.setType(Material.OAK_SIGN, false);
        if (!(signBlock.getState() instanceof Sign sign)) {
            cancelNameEdit(player.getUniqueId());
            player.sendMessage(context.language.chat("event.name-sign-unavailable"));
            return;
        }
        sign.setWaxed(false);
        sign.setAllowedEditorUniqueId(player.getUniqueId());
        sign.getSide(Side.FRONT).line(0, Component.text(event.name()));
        sign.update(true, false);

        Bukkit.getScheduler().runTask(context.plugin, () -> {
            if (nameEditSessions.get(player.getUniqueId()) == session && player.isOnline()) {
                player.openSign(sign, Side.FRONT);
            }
        });
        // If the player escapes the sign editor without submitting, clean up the temporary block.
        Bukkit.getScheduler().runTaskLater(context.plugin, () -> {
            if (nameEditSessions.get(player.getUniqueId()) == session) {
                cancelNameEdit(player.getUniqueId());
                if (player.isOnline()) reopen(player, event.id(), overviewPage);
            }
        }, 1200L);
    }

    private Block findTemporarySignLocation(Player player) {
        Location base = player.getLocation();
        for (int radius = 0; radius <= 3; radius++) {
            for (int yOffset = 0; yOffset >= -2; yOffset--) {
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (radius > 0 && Math.max(Math.abs(x), Math.abs(z)) != radius) continue;
                        Block candidate = base.getWorld().getBlockAt(base.getBlockX() + x,
                                base.getBlockY() + yOffset, base.getBlockZ() + z);
                        if (candidate.getType().isAir()
                                && candidate.getRelative(0, -1, 0).getType().isSolid()) return candidate;
                    }
                }
            }
        }
        return null;
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        NameEditSession session = nameEditSessions.get(playerId);
        if (session == null || !session.location.equals(event.getBlock().getLocation())) return;

        event.setCancelled(true);
        nameEditSessions.remove(playerId);
        restoreTemporarySign(session);
        String newName = PlainTextComponentSerializer.plainText().serialize(event.line(0)).trim();
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(context.plugin, () -> saveEventName(player, session, newName));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cancelNameEdit(event.getPlayer().getUniqueId());
    }

    private void saveEventName(Player player, NameEditSession session, String newName) {
        if (!DatabaseManager.isValidEventName(newName)) {
            player.sendMessage(context.language.chat("event.invalid-name"));
            reopen(player, session.eventId, session.overviewPage);
            return;
        }
        try {
            if (!context.database.updateEventName(session.eventId, newName)) {
                player.sendMessage(context.language.chat("event.already-exists",
                        LanguageManager.placeholders("event", newName)));
                reopen(player, session.eventId, session.overviewPage);
                return;
            }
            player.sendMessage(context.language.chat("event.name-updated",
                    LanguageManager.placeholders("event", newName)));
            reopen(player, session.eventId, session.overviewPage);
        } catch (SQLException exception) {
            reportDatabaseError(player, exception);
            reopen(player, session.eventId, session.overviewPage);
        }
    }

    private void cancelNameEdit(UUID playerId) {
        NameEditSession session = nameEditSessions.remove(playerId);
        if (session != null) restoreTemporarySign(session);
    }

    private void restoreTemporarySign(NameEditSession session) {
        Block block = session.location.getBlock();
        if (block.getType() == Material.OAK_SIGN) block.setBlockData(session.originalData, false);
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

    private record NameEditSession(long eventId, int overviewPage, Location location, BlockData originalData) { }
}
