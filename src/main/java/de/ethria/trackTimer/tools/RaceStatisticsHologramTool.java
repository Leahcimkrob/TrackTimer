package de.ethria.trackTimer.tools;

import de.ethria.trackTimer.database.DatabaseManager.Event;
import de.ethria.trackTimer.gui.EditorGuiContext;
import de.ethria.trackTimer.language.LanguageManager;
import de.ethria.trackTimer.race.RaceStatisticsHologramManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** Gives and handles the two-corner area-selection item for race holograms. */
public final class RaceStatisticsHologramTool implements Listener {
    private final EditorGuiContext context;
    private final RaceStatisticsHologramManager holograms;
    private final NamespacedKey toolKey;
    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();

    public RaceStatisticsHologramTool(EditorGuiContext context, RaceStatisticsHologramManager holograms) {
        this.context = context;
        this.holograms = holograms;
        this.toolKey = new NamespacedKey(context.plugin(), "race_statistics_hologram_tool");
    }

    public void giveTool(Player player, Event event, boolean redstone) {
        giveTool(player, event, redstone, null, null);
    }

    public void giveTool(Player player, Event event, boolean redstone, LocalDate filterDate, LocalTime filterTime) {
        if (!holograms.isAvailable()) {
            player.sendMessage(context.language().chat("race-statistics.hologram-plugin-missing"));
            return;
        }
        ItemStack item = context.configuredIcon(context.topTenSettings(),
                "guis.topten.button-bar.hologram", Material.STICK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(context.language().gui("buttons.hologram.name"));
        var lore = context.language().guiList("buttons.hologram.lore");
        if (!lore.isEmpty()) meta.lore(lore);
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING,
                event.id() + "|" + redstone + "|" + (filterDate == null ? "" : filterDate)
                        + "|" + (filterTime == null ? "" : filterTime));
        item.setItemMeta(meta);
        player.getInventory().addItem(item);
        selections.put(player.getUniqueId(), new Selection(event.id(), redstone, null, filterDate, filterTime));
        player.sendMessage(context.language().chat("race-statistics.hologram-tool-started",
                LanguageManager.placeholders("event", event.name())));
    }

    @EventHandler
    public void onBlockInteract(PlayerInteractEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || !isTool(event.getItem())) return;
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        if (block == null) return;
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null) {
            selection = selectionFromItem(event.getItem());
            if (selection == null) return;
            selections.put(player.getUniqueId(), selection);
        }
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            selections.put(player.getUniqueId(), new Selection(selection.eventId(), selection.redstone(),
                    block.getLocation(), selection.filterDate(), selection.filterTime()));
            player.sendMessage(context.language().chat("race-statistics.hologram-first-corner",
                    LanguageManager.placeholders("x", block.getX(), "y", block.getY(), "z", block.getZ())));
            return;
        }
        if (selection.firstCorner() == null) {
            player.sendMessage(context.language().chat("race-statistics.hologram-select-first-corner"));
            return;
        }

        try {
            Event selectedEvent = context.database().getEvent(selection.eventId());
            if (selectedEvent == null) {
                player.sendMessage(context.language().chat("race-statistics.hologram-event-missing"));
                return;
            }
            if (!selection.firstCorner().getWorld().equals(block.getWorld())) {
                player.sendMessage(context.language().chat("race-statistics.hologram-same-world"));
                return;
            }
            if (!holograms.createBoard(selectedEvent, selection.redstone(), selection.firstCorner(),
                    block.getLocation(), player.getLocation(), selection.filterDate(), selection.filterTime())) {
                player.sendMessage(context.language().chat("race-statistics.hologram-invalid-area"));
                return;
            }
            selections.remove(player.getUniqueId());
            removeTools(player);
            player.sendMessage(context.language().chat("race-statistics.hologram-created",
                    LanguageManager.placeholders("event", selectedEvent.name())));
        } catch (SQLException exception) {
            context.plugin().getLogger().log(Level.SEVERE, "Could not create a race statistics hologram.", exception);
            player.sendMessage(context.language().chat("race-statistics.command-failed"));
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (!selections.containsKey(event.getPlayer().getUniqueId())
                || !event.getMessage().trim().equalsIgnoreCase("exit")) return;
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(context.plugin(), () -> {
            selections.remove(event.getPlayer().getUniqueId());
            removeTools(event.getPlayer());
            event.getPlayer().sendMessage(context.language().chat("race-statistics.hologram-tool-cancelled"));
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        selections.remove(event.getPlayer().getUniqueId());
    }

    private Selection selectionFromItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String value = item.getItemMeta().getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
        if (value == null) return null;
        // Keep already-issued tools usable after adding the optional date/time filter.
        String[] parts = value.contains("|") ? value.split("\\|", -1) : value.split(":", -1);
        if (parts.length != 2 && parts.length != 4) return null;
        try {
            LocalDate filterDate = parts.length == 4 && !parts[2].isEmpty() ? LocalDate.parse(parts[2]) : null;
            LocalTime filterTime = parts.length == 4 && !parts[3].isEmpty()
                    ? LocalTime.parse(parts[3]).withNano(0) : null;
            return new Selection(Long.parseLong(parts[0]), Boolean.parseBoolean(parts[1]), null,
                    filterDate, filterTime);
        } catch (NumberFormatException | DateTimeParseException ignored) {
            return null;
        }
    }

    private boolean isTool(ItemStack item) {
        return selectionFromItem(item) != null;
    }

    private void removeTools(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (isTool(item)) player.getInventory().setItem(slot, null);
        }
    }

    private record Selection(long eventId, boolean redstone, org.bukkit.Location firstCorner,
                             LocalDate filterDate, LocalTime filterTime) { }
}
