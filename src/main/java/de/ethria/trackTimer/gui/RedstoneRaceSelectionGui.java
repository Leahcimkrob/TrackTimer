package de.ethria.trackTimer.gui;

import de.ethria.trackTimer.database.DatabaseManager.Event;
import de.ethria.trackTimer.database.DatabaseManager.RaceStatisticsEntry;
import de.ethria.trackTimer.language.LanguageManager;
import de.ethria.trackTimer.race.RaceStatisticsEvaluator;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/** Lets players choose a redstone race session before opening its TopTen results. */
public final class RedstoneRaceSelectionGui implements Listener {
    private static final int INVENTORY_SIZE = 54;
    private static final int BUTTON_ROW_START = 45;

    private final EditorGuiContext context;
    private final RaceStatisticsEvaluator statistics;
    private final EventOverviewGui overviewGui;
    private final TopTenGui topTenGui;

    public RedstoneRaceSelectionGui(EditorGuiContext context, RaceStatisticsEvaluator statistics,
                                    EventOverviewGui overviewGui, TopTenGui topTenGui) {
        this.context = context;
        this.statistics = statistics;
        this.overviewGui = overviewGui;
        this.topTenGui = topTenGui;
    }

    public void open(Player player, Event event, int overviewPage) {
        open(player, event, overviewPage, 0);
    }

    private void open(Player player, Event event, int overviewPage, int requestedPage) {
        try {
            var evaluation = statistics.evaluate(event.id(), RaceStatisticsEvaluator.Output.GUI);
            if (evaluation.isEmpty()) return;
            var result = evaluation.get();
            RaceSelectionHolder holder = new RaceSelectionHolder(event, overviewPage);
            Inventory inventory = Bukkit.createInventory(holder, INVENTORY_SIZE,
                    context.language.gui("topten.redstone-selection.title",
                            LanguageManager.placeholders("event", event.name())));
            holder.inventory = inventory;

            Map<Long, List<RaceStatisticsEntry>> sessions = new LinkedHashMap<>();
            for (RaceStatisticsEntry entry : result.entries()) {
                long sessionKey = Math.floorDiv(entry.startTimeMillis(), 1000);
                sessions.computeIfAbsent(sessionKey, ignored -> new ArrayList<>()).add(entry);
            }

            int rows = boundedDimension("rows", 5, 1, 5);
            int columns = boundedDimension("columns", 9, 1, 9);
            int firstRow = (5 - rows) / 2;
            int firstColumn = (9 - columns) / 2;
            List<Integer> slots = new ArrayList<>(rows * columns);
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < columns; column++) {
                    slots.add((firstRow + row) * 9 + firstColumn + column);
                }
            }

            Locale locale = Locale.forLanguageTag(context.language.getLocale());
            DateTimeFormatter dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale);
            DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
            ZoneId zone = ZoneId.systemDefault();
            holder.pages = Math.max(1, (sessions.size() + slots.size() - 1) / slots.size());
            holder.page = Math.max(0, Math.min(requestedPage, holder.pages - 1));
            int start = holder.page * slots.size();
            int index = 0;
            for (Map.Entry<Long, List<RaceStatisticsEntry>> session : sessions.entrySet().stream().skip(start).limit(slots.size()).toList()) {
                long startMillis = session.getValue().get(0).startTimeMillis();
                var localDateTime = Instant.ofEpochMilli(startMillis).atZone(zone);
                List<RaceStatisticsEntry> podium = session.getValue().stream()
                        .sorted(Comparator.comparingLong(RaceStatisticsEntry::raceTimeMillis)
                                .thenComparing(RaceStatisticsEntry::playerName, String.CASE_INSENSITIVE_ORDER)
                                .thenComparingLong(RaceStatisticsEntry::resultId))
                        .toList();
                ItemStack watch = context.configuredIcon(context.topTenSettings,
                        "guis.topten.redstone-selection.item", Material.CLOCK);
                watch.setAmount(Math.min(start + index + 1, watch.getMaxStackSize()));
                ItemMeta meta = watch.getItemMeta();
                meta.displayName(context.language.gui("topten.redstone-race.name",
                        LanguageManager.placeholders("rank", start + index + 1)));
                meta.lore(context.language.guiList("topten.redstone-race.lore", LanguageManager.placeholders(
                        "first", place(podium, 0), "second", place(podium, 1), "third", place(podium, 2),
                        "date", dateFormat.format(localDateTime),
                        "time_of_day", timeFormat.format(localDateTime))));
                watch.setItemMeta(meta);
                int slot = slots.get(index);
                inventory.setItem(slot, watch);
                LocalDate date = localDateTime.toLocalDate();
                LocalTime time = localDateTime.toLocalTime().withNano(0);
                holder.choices.put(slot, new RaceChoice(date, time));
                index++;
            }

            if (holder.choices.isEmpty()) {
                ItemStack empty = new ItemStack(Material.BARRIER);
                ItemMeta meta = empty.getItemMeta();
                meta.displayName(context.language.gui("topten.redstone-selection.empty-item.name"));
                empty.setItemMeta(meta);
                inventory.setItem(22, empty);
            }

            Material filler = context.material(context.topTenSettings.getString(
                    "guis.topten.redstone-selection.button-bar.material"), Material.GRAY_STAINED_GLASS_PANE);
            for (int slot = BUTTON_ROW_START; slot < INVENTORY_SIZE; slot++) inventory.setItem(slot, new ItemStack(filler));
            int backSlot = backSlot();
            ItemStack backButton = context.configuredIcon(context.topTenSettings,
                    "guis.topten.redstone-selection.button-bar.back", Material.ARROW);
            inventory.setItem(backSlot, named(backButton, "buttons.back"));

            if (holder.page > 0) inventory.setItem(navigationSlot("previous-page", 45), named(context.configuredIcon(context.topTenSettings,
                    "guis.topten.redstone-selection.button-bar.previous-page", Material.ARROW), "buttons.previous-page"));
            if (holder.page + 1 < holder.pages) inventory.setItem(navigationSlot("next-page", 53), named(context.configuredIcon(context.topTenSettings,
                    "guis.topten.redstone-selection.button-bar.next-page", Material.ARROW), "buttons.next-page"));
            player.openInventory(inventory);
        } catch (SQLException exception) {
            context.plugin.getLogger().log(Level.SEVERE,
                    "Could not load redstone races for event '" + event.name() + "'.", exception);
            player.sendMessage(context.language.chat("race-statistics.command-failed"));
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof RaceSelectionHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != event.getView().getTopInventory()) return;

        if (event.getRawSlot() == navigationSlot("previous-page", 45) && holder.page > 0) {
            open(player, holder.event, holder.overviewPage, holder.page - 1);
            return;
        }
        if (event.getRawSlot() == navigationSlot("next-page", 53) && holder.page + 1 < holder.pages) {
            open(player, holder.event, holder.overviewPage, holder.page + 1);
            return;
        }
        if (event.getRawSlot() == backSlot()) {
            overviewGui.open(player, holder.overviewPage);
            return;
        }
        RaceChoice choice = holder.choices.get(event.getRawSlot());
        if (choice != null) {
            topTenGui.open(player, holder.event, holder.overviewPage, choice.date, choice.time);
        }
    }

    private int backSlot() {
        int slot = context.topTenSettings.getInt("guis.topten.redstone-selection.button-bar.back.slot", 49);
        return slot >= BUTTON_ROW_START && slot < INVENTORY_SIZE ? slot : 49;
    }

    private int navigationSlot(String key, int fallback) {
        int slot = context.topTenSettings.getInt("guis.topten.redstone-selection.button-bar." + key + ".slot", fallback);
        return slot >= BUTTON_ROW_START && slot < INVENTORY_SIZE ? slot : fallback;
    }

    private String place(List<RaceStatisticsEntry> podium, int index) {
        return index < podium.size() ? podium.get(index).playerName() : "-";
    }

    private int boundedDimension(String key, int fallback, int minimum, int maximum) {
        int value = context.topTenSettings.getInt("guis.topten.redstone-selection." + key, fallback);
        if (value >= minimum && value <= maximum) return value;
        context.plugin.getLogger().warning("Invalid redstone race selection GUI " + key + " value " + value
                + "; using " + fallback + ".");
        return fallback;
    }

    private ItemStack named(ItemStack item, String key) {
        ItemMeta meta = item.getItemMeta();
        meta.displayName(context.language.gui(key + ".name"));
        List<net.kyori.adventure.text.Component> lore = context.language.guiList(key + ".lore");
        if (!lore.isEmpty()) meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private record RaceChoice(LocalDate date, LocalTime time) { }

    private static final class RaceSelectionHolder implements InventoryHolder {
        private final Event event;
        private final int overviewPage;
        private int page;
        private int pages;
        private final Map<Integer, RaceChoice> choices = new LinkedHashMap<>();
        private Inventory inventory;

        private RaceSelectionHolder(Event event, int overviewPage) {
            this.event = event;
            this.overviewPage = overviewPage;
        }

        @Override public Inventory getInventory() { return inventory; }
    }
}
