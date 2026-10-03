package de.ethria.trackTimer.gui;

import de.ethria.trackTimer.database.DatabaseManager;
import de.ethria.trackTimer.database.DatabaseManager.Event;
import de.ethria.trackTimer.database.DatabaseManager.RaceCheckpointSplit;
import de.ethria.trackTimer.database.DatabaseManager.RaceLapTime;
import de.ethria.trackTimer.database.DatabaseManager.RaceStatisticsEntry;
import de.ethria.trackTimer.database.DatabaseManager.PlayerRaceLapTime;
import de.ethria.trackTimer.language.LanguageManager;
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

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/** Shows a player's race days with the fastest run pinned to the first row. */
public final class PlayerDetailsGui implements Listener {
    private static final int INVENTORY_SIZE = 54;
    private static final int BUTTON_ROW_START = 45;

    private final EditorGuiContext context;

    public PlayerDetailsGui(EditorGuiContext context) {
        this.context = context;
    }

    public void open(Player player, Event event, RaceStatisticsEntry selectedEntry,
                     int overviewPage, Runnable backAction) {
        try {
            List<RaceStatisticsEntry> results = context.database().listPlayerRaceStatistics(
                    event.id(), selectedEntry.playerUuid());
            List<PlayerRaceLapTime> lapRows = context.database().listPlayerRaceLapTimes(
                    event.id(), selectedEntry.playerUuid());
            Map<Long, List<RaceLapTime>> lapsByResult = new HashMap<>();
            for (PlayerRaceLapTime row : lapRows) {
                lapsByResult.computeIfAbsent(row.raceResultId(), ignored -> new ArrayList<>())
                        .add(new RaceLapTime(row.lap(), row.lapTimeMillis()));
            }

            List<RaceRun> runs = results.stream()
                    .map(result -> new RaceRun(result, List.copyOf(
                            lapsByResult.getOrDefault(result.resultId(), List.of()))))
                    .toList();
            RaceRun fastestRun = runs.stream().filter(run -> run.fastestLap() != null)
                    .min(Comparator.comparingLong((RaceRun run) -> run.fastestLap().lapTimeMillis())
                            .thenComparingLong(run -> run.result().raceTimeMillis())
                            .thenComparingLong(run -> run.result().startTimeMillis()))
                    .orElse(null);
            int recentRaceLimit = context.plugin.getConfig().getInt(
                    "race-statistics.player-details.recent-races-limit", 5);
            var recentRunsQuery = runs.stream()
                    .filter(run -> fastestRun == null || run.result().resultId() != fastestRun.result().resultId());
            List<RaceRun> recentRuns = (recentRaceLimit > 0
                    ? recentRunsQuery.limit(recentRaceLimit) : recentRunsQuery).toList();

            render(player, event, selectedEntry.playerName(), fastestRun, recentRuns,
                    0, 0, overviewPage, backAction);
        } catch (SQLException exception) {
            context.plugin.getLogger().log(Level.SEVERE,
                    "Could not load player race details for '" + selectedEntry.playerName() + "'.", exception);
            player.sendMessage(context.language.chat("race-statistics.command-failed"));
        }
    }

    private void render(Player player, Event event, String playerName, RaceRun fastestRun,
                        List<RaceRun> recentRuns, int historyPage, int roundsPage,
                        int overviewPage, Runnable backAction) throws SQLException {
        int rows = boundedDimension("rows", 5, 1, 5);
        int columns = boundedDimension("columns", 9, 2, 9);
        int firstRow = (5 - rows) / 2;
        int firstColumn = (9 - columns) / 2;
        List<Integer> columnsSlots = new ArrayList<>(columns);
        for (int column = 0; column < columns; column++) columnsSlots.add(firstColumn + column);

        int recentRowsPerPage = rows - 1;
        int historyPageCount = recentRowsPerPage == 0 ? 1
                : Math.max(1, (recentRuns.size() + recentRowsPerPage - 1) / recentRowsPerPage);
        int boundedHistoryPage = Math.min(historyPage, historyPageCount - 1);
        List<RaceRun> visibleRuns = new ArrayList<>();
        if (fastestRun != null) visibleRuns.add(fastestRun);
        if (recentRowsPerPage > 0) {
            int from = boundedHistoryPage * recentRowsPerPage;
            recentRuns.stream().skip(from).limit(recentRowsPerPage).forEach(visibleRuns::add);
        }

        Map<Long, List<RaceCheckpointSplit>> splitsByResult = new HashMap<>();
        for (RaceRun run : visibleRuns) {
            splitsByResult.put(run.result().resultId(),
                    context.database().listRaceCheckpointSplits(run.result().resultId()));
        }
        int roundsPerPage = columns - 1;
        int roundsPageCount = 1;
        for (RaceRun run : visibleRuns) {
            roundsPageCount = Math.max(roundsPageCount,
                    Math.max(1, (run.laps().size() + roundsPerPage - 1) / roundsPerPage));
        }
        int boundedRoundsPage = Math.min(roundsPage, roundsPageCount - 1);

        DetailsHolder holder = new DetailsHolder(event, playerName, fastestRun, recentRuns,
                boundedHistoryPage, boundedRoundsPage, historyPageCount, roundsPageCount,
                overviewPage, backAction);
        Inventory inventory = Bukkit.createInventory(holder, INVENTORY_SIZE,
                context.language.gui("topten.player-details.title", LanguageManager.placeholders(
                        "event", event.name(), "player", playerName)));
        holder.inventory = inventory;

        if (fastestRun != null) {
            renderRunRow(inventory, fastestRun, firstRow, firstColumn, columns,
                    boundedRoundsPage, splitsByResult.get(fastestRun.result().resultId()));
        } else if (!columnsSlots.isEmpty()) {
            ItemStack empty = new ItemStack(Material.BARRIER);
            ItemMeta meta = empty.getItemMeta();
            meta.displayName(context.language.gui("topten.player-details.empty-item.name"));
            empty.setItemMeta(meta);
            inventory.setItem(firstRow * 9 + columnsSlots.get(0), empty);
        }

        if (recentRowsPerPage > 0) {
            int from = boundedHistoryPage * recentRowsPerPage;
            List<RaceRun> pageRuns = recentRuns.stream().skip(from).limit(recentRowsPerPage).toList();
            for (int index = 0; index < pageRuns.size(); index++) {
                RaceRun run = pageRuns.get(index);
                int row = firstRow + index + 1;
                renderRunRow(inventory, run, row, firstColumn, columns, boundedRoundsPage,
                        splitsByResult.get(run.result().resultId()));
            }
        }

        fillButtonBar(inventory);
        if (boundedHistoryPage > 0) {
            inventory.setItem(buttonSlot("previous-races", 45), button("previous-races", "buttons.previous-page"));
        }
        if (boundedRoundsPage > 0) {
            inventory.setItem(buttonSlot("previous-rounds", 46), button("previous-rounds", "buttons.previous-page"));
        }
        int backSlot = buttonSlot("back", 49);
        inventory.setItem(backSlot, button("back", "buttons.back"));
        if (boundedRoundsPage + 1 < roundsPageCount) {
            inventory.setItem(buttonSlot("next-rounds", 52), button("next-rounds", "buttons.next-page"));
        }
        if (boundedHistoryPage + 1 < historyPageCount) {
            inventory.setItem(buttonSlot("next-races", 53), button("next-races", "buttons.next-page"));
        }
        player.openInventory(inventory);
    }

    private void renderRunRow(Inventory inventory, RaceRun run, int row,
                              int firstColumn, int columns, int roundsPage,
                              List<RaceCheckpointSplit> checkpointSplits) {
        RaceStatisticsEntry result = run.result();
        RaceLapTime bestLap = run.fastestLap();
        if (bestLap == null) return;

        DateTimeFormatter dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(Locale.forLanguageTag(context.language.getLocale()));
        DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
        var raceDateTime = Instant.ofEpochMilli(result.startTimeMillis()).atZone(ZoneId.systemDefault());
        ItemStack fastest = context.configuredIcon(context.topTenSettings,
                "guis.player-details.items.fastest-lap", Material.CLOCK);
        ItemMeta fastestMeta = fastest.getItemMeta();
        fastestMeta.displayName(context.language.gui("topten.player-details.fastest-lap.name"));
        List<Component> fastestLore = new ArrayList<>(context.language.guiList("topten.player-details.fastest-lap.lore",
                LanguageManager.placeholders("player", result.playerName(),
                        "total_time", formatDuration(result.raceTimeMillis()),
                        "lap_time", formatDuration(bestLap.lapTimeMillis()), "lap", bestLap.lap(),
                        "date", dateFormat.format(raceDateTime), "time_of_day", timeFormat.format(raceDateTime))));
        fastestLore.addAll(checkpointLore(run, checkpointSplits, bestLap.lap()));
        fastestMeta.lore(fastestLore);
        fastest.setItemMeta(fastestMeta);
        inventory.setItem(row * 9 + firstColumn, fastest);

        List<RaceLapTime> allLaps = run.laps().stream()
                .sorted(Comparator.comparingInt(RaceLapTime::lap))
                .toList();
        int roundsPerPage = columns - 1;
        int from = roundsPage * roundsPerPage;
        for (int index = 0; index < roundsPerPage && from + index < allLaps.size(); index++) {
            RaceLapTime lap = allLaps.get(from + index);
            ItemStack lapItem = context.configuredIcon(context.topTenSettings,
                    "guis.player-details.items.lap", Material.PAPER);
            ItemMeta lapMeta = lapItem.getItemMeta();
            lapMeta.displayName(context.language.gui("topten.player-details.lap.name",
                    LanguageManager.placeholders("lap", lap.lap())));
            List<Component> lapLore = new ArrayList<>(context.language.guiList("topten.player-details.lap.lore",
                    LanguageManager.placeholders("lap", lap.lap(),
                            "lap_time", formatDuration(lap.lapTimeMillis()))));
            lapLore.addAll(checkpointLore(run, checkpointSplits, lap.lap()));
            lapMeta.lore(lapLore);
            lapItem.setItemMeta(lapMeta);
            inventory.setItem(row * 9 + firstColumn + index + 1, lapItem);
        }
    }

    private List<Component> checkpointLore(RaceRun run, List<RaceCheckpointSplit> splits, int lap) {
        long lapStartElapsed = run.laps().stream().filter(row -> row.lap() < lap)
                .mapToLong(RaceLapTime::lapTimeMillis).sum();
        long previousElapsed = lapStartElapsed;
        List<Component> lore = new ArrayList<>();
        for (RaceCheckpointSplit split : splits.stream().filter(row -> row.lap() == lap)
                .sorted(Comparator.comparingInt(RaceCheckpointSplit::checkpointOrder)).toList()) {
            long driveTime = Math.max(0, split.elapsedMillis() - previousElapsed);
            lore.add(context.language.gui("topten.player-details.checkpoint-line",
                    LanguageManager.placeholders("order", split.checkpointOrder(),
                            "time", formatDuration(driveTime))));
            previousElapsed = split.elapsedMillis();
        }
        return lore;
    }

    private void fillButtonBar(Inventory inventory) {
        Material filler = context.material(context.topTenSettings.getString(
                "guis.player-details.button-bar.material"), Material.GRAY_STAINED_GLASS_PANE);
        for (int slot = BUTTON_ROW_START; slot < INVENTORY_SIZE; slot++) {
            inventory.setItem(slot, new ItemStack(filler));
        }
    }

    private ItemStack button(String key, String labelKey) {
        ItemStack item = context.configuredIcon(context.topTenSettings,
                "guis.player-details.button-bar." + key, Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(context.language.gui(labelKey + ".name"));
        List<Component> lore = context.language.guiList(labelKey + ".lore");
        if (!lore.isEmpty()) meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private int buttonSlot(String key, int fallback) {
        int slot = context.topTenSettings.getInt("guis.player-details.button-bar." + key + ".slot", fallback);
        return slot >= BUTTON_ROW_START && slot < INVENTORY_SIZE ? slot : fallback;
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
        int value = context.topTenSettings.getInt("guis.player-details." + key, fallback);
        if (value >= minimum && value <= maximum) return value;
        context.plugin.getLogger().warning("Invalid player-details GUI " + key + " value " + value
                + "; using " + fallback + ".");
        return fallback;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof DetailsHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != event.getView().getTopInventory()) return;

        int slot = event.getRawSlot();
        if (slot == buttonSlot("back", 49)) {
            holder.backAction.run();
        } else if (slot == buttonSlot("previous-races", 45) && holder.historyPage > 0) {
            redraw(player, holder, holder.historyPage - 1, 0);
        } else if (slot == buttonSlot("next-races", 53) && holder.historyPage + 1 < holder.historyPageCount) {
            redraw(player, holder, holder.historyPage + 1, 0);
        } else if (slot == buttonSlot("previous-rounds", 46) && holder.roundsPage > 0) {
            redraw(player, holder, holder.historyPage, holder.roundsPage - 1);
        } else if (slot == buttonSlot("next-rounds", 52) && holder.roundsPage + 1 < holder.roundsPageCount) {
            redraw(player, holder, holder.historyPage, holder.roundsPage + 1);
        }
    }

    private void redraw(Player player, DetailsHolder holder, int historyPage, int roundsPage) {
        try {
            render(player, holder.event, holder.playerName, holder.fastestRun, holder.recentRuns,
                    historyPage, roundsPage, holder.overviewPage, holder.backAction);
        } catch (SQLException exception) {
            context.plugin.getLogger().log(Level.SEVERE, "Could not load race checkpoint details.", exception);
            player.sendMessage(context.language.chat("race-statistics.command-failed"));
        }
    }

    private record RaceRun(RaceStatisticsEntry result, List<RaceLapTime> laps) {
        private RaceLapTime fastestLap() {
            return laps.stream().min(Comparator.comparingLong(RaceLapTime::lapTimeMillis)
                    .thenComparingInt(RaceLapTime::lap)).orElse(null);
        }
    }

    private static final class DetailsHolder implements InventoryHolder {
        private final Event event;
        private final String playerName;
        private final RaceRun fastestRun;
        private final List<RaceRun> recentRuns;
        private final int historyPage;
        private final int roundsPage;
        private final int historyPageCount;
        private final int roundsPageCount;
        private final int overviewPage;
        private final Runnable backAction;
        private Inventory inventory;

        private DetailsHolder(Event event, String playerName, RaceRun fastestRun,
                              List<RaceRun> recentRuns, int historyPage, int roundsPage,
                              int historyPageCount, int roundsPageCount, int overviewPage,
                              Runnable backAction) {
            this.event = event;
            this.playerName = playerName;
            this.fastestRun = fastestRun;
            this.recentRuns = recentRuns;
            this.historyPage = historyPage;
            this.roundsPage = roundsPage;
            this.historyPageCount = historyPageCount;
            this.roundsPageCount = roundsPageCount;
            this.overviewPage = overviewPage;
            this.backAction = backAction;
        }

        @Override public Inventory getInventory() { return inventory; }
    }
}
