package de.ethria.trackTimer.gui;

import de.ethria.trackTimer.database.DatabaseManager.Event;
import de.ethria.trackTimer.database.DatabaseManager.RaceStatisticsEntry;
import de.ethria.trackTimer.language.LanguageManager;
import de.ethria.trackTimer.race.RaceStatisticsEvaluator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
import org.bukkit.inventory.meta.SkullMeta;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/** Six-row results GUI for one event. */
public final class TopTenGui implements Listener {
    private static final int INVENTORY_SIZE = 54;
    private static final int BUTTON_ROW_START = 45;
    private final EditorGuiContext context;
    private final RaceStatisticsEvaluator statistics;
    private final EventOverviewGui overviewGui;
    private RedstoneRaceSelectionGui redstoneRaceSelectionGui;

    public TopTenGui(EditorGuiContext context, RaceStatisticsEvaluator statistics, EventOverviewGui overviewGui) {
        this.context = context;
        this.statistics = statistics;
        this.overviewGui = overviewGui;
    }

    public void setRedstoneRaceSelectionGui(RedstoneRaceSelectionGui redstoneRaceSelectionGui) {
        this.redstoneRaceSelectionGui = redstoneRaceSelectionGui;
    }

    public void open(Player player, Event event, int overviewPage) {
        open(player, event, overviewPage, null, null);
    }

    public void open(Player player, Event event, int overviewPage, LocalDate dateFilter, LocalTime timeFilter) {
        try {
            var evaluation = statistics.evaluate(event.id(), RaceStatisticsEvaluator.Output.GUI,
                    dateFilter, timeFilter);
            if (evaluation.isEmpty()) return;
            var results = evaluation.get();
            TopTenHolder holder = new TopTenHolder(event, overviewPage);
            Inventory inventory = Bukkit.createInventory(holder, INVENTORY_SIZE,
                    context.language.gui("topten.title", LanguageManager.placeholders("event", event.name())));
            holder.inventory = inventory;

            int rows = boundedDimension("rows", 3, 1, 5);
            int columns = boundedDimension("columns", 5, 1, 9);
            int firstRow = (5 - rows) / 2;
            int firstColumn = (9 - columns) / 2;
            List<Integer> resultSlots = new ArrayList<>(rows * columns);
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < columns; column++) {
                    resultSlots.add((firstRow + row) * 9 + firstColumn + column);
                }
            }

            DateTimeFormatter dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                    .withLocale(Locale.forLanguageTag(context.language.getLocale()));
            DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
            int count = Math.min(results.entries().size(), resultSlots.size());
            for (int index = 0; index < count; index++) {
                RaceStatisticsEntry entry = results.entries().get(index);
                int rank = index + 1;
                ItemStack head = playerHead(entry.playerUuid());
                ItemMeta meta = head.getItemMeta();
                meta.displayName(context.language.gui("topten.entry-item.name",
                        LanguageManager.placeholders("rank", rank)));
                var placeholders = LanguageManager.placeholders(
                        "rank", rank,
                        "player", entry.playerName(),
                        "race_time", formatDuration(entry.raceTimeMillis()),
                        "laps", entry.lapsCompleted(),
                        "races", results.racesDrivenBy(entry.playerUuid()),
                        "date", dateFormat.format(Instant.ofEpochMilli(entry.startTimeMillis()).atZone(ZoneId.systemDefault())),
                        "time_of_day", timeFormat.format(Instant.ofEpochMilli(entry.startTimeMillis()).atZone(ZoneId.systemDefault())));
                String loreKey = results.redstone() ? "topten.redstone-entry.lore" : "topten.entry-item.lore";
                meta.lore(context.language.guiList(loreKey, placeholders));
                head.setItemMeta(meta);
                inventory.setItem(resultSlots.get(index), head);
            }

            if (results.entries().isEmpty()) {
                ItemStack empty = new ItemStack(Material.BARRIER);
                ItemMeta meta = empty.getItemMeta();
                meta.displayName(context.language.gui("topten.empty-item.name"));
                empty.setItemMeta(meta);
                inventory.setItem(22, empty);
            }

            Material filler = context.material(context.topTenSettings.getString("guis.topten.button-bar.material"),
                    Material.GRAY_STAINED_GLASS_PANE);
            for (int slot = BUTTON_ROW_START; slot < INVENTORY_SIZE; slot++) {
                inventory.setItem(slot, new ItemStack(filler));
            }
            int backSlot = context.topTenSettings.getInt("guis.topten.button-bar.back.slot", 49);
            if (backSlot < BUTTON_ROW_START || backSlot >= INVENTORY_SIZE) backSlot = 49;
            ItemStack backButton = context.configuredIcon(context.topTenSettings,
                    "guis.topten.button-bar.back", Material.ARROW);
            inventory.setItem(backSlot, named(backButton, "buttons.back"));
            player.openInventory(inventory);
        } catch (SQLException exception) {
            context.plugin.getLogger().log(Level.SEVERE,
                    "Could not load top-ten results for event '" + event.name() + "'.", exception);
            player.sendMessage(context.language.chat("race-statistics.command-failed"));
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof TopTenHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != event.getView().getTopInventory()) return;
        int backSlot = context.topTenSettings.getInt("guis.topten.button-bar.back.slot", 49);
        if (backSlot < BUTTON_ROW_START || backSlot >= INVENTORY_SIZE) backSlot = 49;
        if (event.getRawSlot() == backSlot) {
            if (holder.redstoneRace && redstoneRaceSelectionGui != null) {
                redstoneRaceSelectionGui.open(player, holder.event, holder.overviewPage);
            } else {
                overviewGui.open(player, holder.overviewPage);
            }
        }
    }

    private ItemStack playerHead(String playerUuid) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (!(item.getItemMeta() instanceof SkullMeta meta)) return item;
        try {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(UUID.fromString(playerUuid)));
            item.setItemMeta(meta);
        } catch (IllegalArgumentException exception) {
            context.plugin.getLogger().warning("Invalid player UUID in a race result: " + playerUuid);
        }
        return item;
    }

    private ItemStack named(ItemStack item, String key) {
        ItemMeta meta = item.getItemMeta();
        meta.displayName(context.language.gui(key + ".name"));
        List<Component> lore = context.language.guiList(key + ".lore");
        if (!lore.isEmpty()) meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private String formatDuration(long durationMillis) {
        long time = Math.max(0, durationMillis);
        Component formatted = context.language.chatFragment("race-statistics.duration-format",
                LanguageManager.placeholders(
                        "hours", String.format(Locale.ROOT, "%02d", time / 3_600_000),
                        "minutes", String.format(Locale.ROOT, "%02d", time / 60_000 % 60),
                        "seconds", String.format(Locale.ROOT, "%02d", time / 1_000 % 60),
                        "centiseconds", String.format(Locale.ROOT, "%02d", time / 10 % 100)));
        return PlainTextComponentSerializer.plainText().serialize(formatted);
    }

    private int boundedDimension(String key, int fallback, int minimum, int maximum) {
        int value = context.topTenSettings.getInt("guis.topten." + key, fallback);
        if (value >= minimum && value <= maximum) return value;
        context.plugin.getLogger().warning("Invalid TopTen GUI " + key + " value " + value
                + "; using " + fallback + ".");
        return fallback;
    }

    private static final class TopTenHolder implements InventoryHolder {
        private final int overviewPage;
        private Inventory inventory;

        private final Event event;
        private final boolean redstoneRace;

        private TopTenHolder(Event event, int overviewPage) {
            this.event = event;
            this.overviewPage = overviewPage;
            this.redstoneRace = "signal".equals(event.startMode());
        }

        @Override public Inventory getInventory() { return inventory; }
    }
}
