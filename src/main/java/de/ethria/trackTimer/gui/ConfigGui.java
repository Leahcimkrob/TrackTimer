package de.ethria.trackTimer.gui;

import de.ethria.trackTimer.TrackTimer;
import de.ethria.trackTimer.command.ReloadSubCommand;
import de.ethria.trackTimer.language.LanguageManager;
import net.kyori.adventure.text.Component;
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

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.logging.Level;

/** Edits live configuration values; closing saves the changes and invokes the reload routine. */
public final class ConfigGui implements Listener {
    public static final String PERMISSION = "tracktimer.command.config";
    private record Setting(String key, String path, int slot, Material material,
                           double step, double min, double max, List<String> choices) { }
    private static final List<Setting> SETTINGS = List.of(
            choice("language", "language", 4, Material.BOOK, List.of()),
            number("height", "race.start-trigger-height-tolerance", 11, Material.SCAFFOLDING, 0.1, 0, 512),
            number("join-timeout", "race.redstone-start-join-timeout-seconds", 13, Material.CLOCK, 1, 0, 86400),
            number("title-radius", "race.redstone-start-title-radius-blocks", 15, Material.BELL, 1, 0, 100000),
            number("gui-normal", "race-statistics.gui.normal-races", 20, Material.PLAYER_HEAD, 1, 0, Integer.MAX_VALUE),
            number("gui-signal", "race-statistics.gui.redstone-races", 22, Material.CLOCK, 1, 0, Integer.MAX_VALUE),
            number("player-details", "race-statistics.player-details.recent-races-limit", 24, Material.WRITABLE_BOOK, 1, 0, Integer.MAX_VALUE),
            number("hologram-normal", "race-statistics.hologram.normal-races", 28, Material.ARMOR_STAND, 1, 0, Integer.MAX_VALUE),
            number("hologram-signal", "race-statistics.hologram.redstone-races", 30, Material.REDSTONE_LAMP, 1, 0, Integer.MAX_VALUE),
            number("tool-timeout", "race-statistics.hologram.delete-tool-timeout-seconds", 32, Material.SHEARS, 1, 1, 86400),
            choice("provider", "race-statistics.hologram.provider", 34, Material.BEACON, List.of("auto", "cmi", "decentholograms")),
            number("chat-normal", "race-statistics.chat.normal-races", 38, Material.PAPER, 1, 0, Integer.MAX_VALUE),
            number("chat-signal", "race-statistics.chat.redstone-races", 40, Material.CLOCK, 1, 0, Integer.MAX_VALUE),
            choice("sort", "race-statistics.chat.redstone-sort-order", 42, Material.ARROW, List.of("descending", "ascending")));

    private final EditorGuiContext context;

    public ConfigGui(EditorGuiContext context) { this.context = context; }

    public void open(Player player) {
        if (!player.hasPermission(PERMISSION)) return;
        Holder holder = new Holder();
        holder.inventory = Bukkit.createInventory(holder, 54, context.language.gui("config-editor.title"));
        for (Setting setting : SETTINGS) {
            Object value = context.plugin.getConfig().get(setting.path);
            holder.values.put(setting.path, value == null ? (setting.step > 0 ? setting.min : "") : value);
            int slot = context.slot(context.configSettings, "items." + setting.key + ".slot", setting.slot, 45);
            holder.settings.put(slot, setting);
            render(holder, slot, setting);
        }
        List<String> rowLabels = List.of("language", "race", "statistics", "hologram", "chat");
        List<Material> backgrounds = List.of(Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                Material.GREEN_STAINED_GLASS_PANE, Material.YELLOW_STAINED_GLASS_PANE,
                Material.PURPLE_STAINED_GLASS_PANE, Material.ORANGE_STAINED_GLASS_PANE);
        for (int row = 0; row < rowLabels.size(); row++) {
            String key = rowLabels.get(row);
            int slot = context.slot(context.configSettings, "row-labels." + key + ".slot", row * 9, 45);
            ItemStack sign = context.configuredIcon(context.configSettings, "row-labels." + key, Material.OAK_SIGN);
            ItemMeta signMeta = sign.getItemMeta();
            signMeta.displayName(context.language.gui("config-editor.row-labels." + key));
            signMeta.lore(List.of());
            sign.setItemMeta(signMeta);
            holder.settings.remove(slot);
            holder.inventory.setItem(slot, sign);
            ItemStack background = context.configuredIcon(context.configSettings,
                    "backgrounds." + key, backgrounds.get(row));
            ItemMeta backgroundMeta = background.getItemMeta();
            backgroundMeta.displayName(Component.empty());
            backgroundMeta.lore(List.of());
            background.setItemMeta(backgroundMeta);
            for (int column = 0; column < 9; column++) {
                int backgroundSlot = row * 9 + column;
                if (holder.inventory.getItem(backgroundSlot) == null) {
                    holder.inventory.setItem(backgroundSlot, background.clone());
                }
            }
        }
        ItemStack filler = context.configuredIcon(context.configSettings, "button-bar.filler", Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.displayName(Component.empty());
        filler.setItemMeta(fillerMeta);
        for (int slot = 45; slot < 54; slot++) holder.inventory.setItem(slot, filler.clone());
        holder.closeSlot = Math.max(45, context.slot(context.configSettings, "button-bar.close.slot", 49, 54));
        ItemStack close = context.configuredIcon(context.configSettings, "button-bar.close", Material.BARRIER);
        ItemMeta meta = close.getItemMeta();
        meta.displayName(context.language.gui("config-editor.close.name"));
        meta.lore(context.language.guiList("config-editor.close.lore"));
        close.setItemMeta(meta);
        holder.inventory.setItem(holder.closeSlot, close);
        player.openInventory(holder.inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.hasPermission(PERMISSION)
                || event.getClickedInventory() != holder.inventory) return;
        if (event.getRawSlot() == holder.closeSlot) { player.closeInventory(); return; }
        if (!event.getClick().isLeftClick() && !event.getClick().isRightClick()) return;
        Setting setting = holder.settings.get(event.getRawSlot());
        if (setting == null) return;
        Object value;
        if (setting.step > 0) {
            double current = ((Number) holder.values.get(setting.path)).doubleValue();
            double next = BigDecimal.valueOf(current).add(BigDecimal.valueOf(
                    event.getClick().isRightClick() ? setting.step : -setting.step)).doubleValue();
            next = Math.max(setting.min, Math.min(setting.max, next));
            if (setting.step < 1) value = next;
            else value = (int) next;
        } else {
            List<String> options = setting.key.equals("language") ? languages() : setting.choices;
            String current = String.valueOf(holder.values.get(setting.path));
            value = options.get((options.indexOf(current) + 1) % options.size());
        }
        holder.values.put(setting.path, value);
        holder.changed.put(setting.path, value);
        render(holder, event.getRawSlot(), setting);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)
                || !(event.getPlayer() instanceof Player player) || !context.plugin.isEnabled()) return;
        if (!player.hasPermission(PERMISSION)) return;
        Map<String, Object> previous = new LinkedHashMap<>();
        holder.changed.forEach((path, value) -> {
            previous.put(path, context.plugin.getConfig().get(path));
            context.plugin.getConfig().set(path, value);
        });
        try {
            context.plugin.getConfig().save(new File(context.plugin.getDataFolder(), "config.yml"));
        } catch (IOException exception) {
            previous.forEach((path, value) -> context.plugin.getConfig().set(path, value));
            context.plugin.getLogger().log(Level.SEVERE, "Could not save GUI configuration changes.", exception);
            player.sendMessage(context.language.chat("config-editor.save-failed"));
            return;
        }
        Bukkit.getScheduler().runTask(context.plugin,
                () -> new ReloadSubCommand((TrackTimer) context.plugin, context.language).execute(player));
    }

    private void render(Holder holder, int slot, Setting setting) {
        Object value = holder.values.get(setting.path);
        String iconPath = "items." + setting.key;
        if (setting.key.equals("sort")) iconPath = "items.sort." + value;
        else if (setting.key.equals("language")
                && context.configSettings.isConfigurationSection("items.language." + value)) {
            iconPath = "items.language." + value;
        }
        ItemStack item = context.configuredIcon(context.configSettings, iconPath, setting.material);
        if (value instanceof Number number) item.setAmount(Math.max(1, Math.min(64, number.intValue())));
        ItemMeta meta = item.getItemMeta();
        String formatted = value instanceof Number number
                ? BigDecimal.valueOf(number.doubleValue()).stripTrailingZeros().toPlainString() : String.valueOf(value);
        Component label = context.language.gui("config-editor.items." + setting.key + ".name");
        meta.displayName(setting.step > 0 ? context.language.gui("config-editor.number-name",
                LanguageManager.placeholders("label", label, "value", formatted)) : label);
        Object displayed = setting.key.equals("sort")
                ? context.language.gui("config-editor.sort." + value) : formatted;
        List<Component> lore = new ArrayList<>(context.language.guiList("config-editor.items." + setting.key + ".lore"));
        lore.addAll(context.language.guiList(setting.step > 0 ? "config-editor.number-lore" : "config-editor.choice-lore",
                LanguageManager.placeholders("value", displayed, "step", setting.step < 1 ? "0.1" : "1")));
        meta.lore(lore);
        item.setItemMeta(meta);
        holder.inventory.setItem(slot, item);
    }

    private List<String> languages() {
        TreeSet<String> locales = new TreeSet<>(List.of("de-DE", "en-US"));
        var languageItems = context.configSettings.getConfigurationSection("items.language");
        if (languageItems != null) {
            for (String key : languageItems.getKeys(false)) {
                if (languageItems.isConfigurationSection(key)) locales.add(key);
            }
        }
        File folder = new File(context.plugin.getDataFolder(), "language");
        File[] files = folder.listFiles();
        if (files != null) for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".yml") && !name.endsWith("_gui.yml")) {
                String locale = name.substring(0, name.length() - 4);
                if (new File(folder, locale + "_gui.yml").isFile()) locales.add(locale);
            }
        }
        return List.copyOf(locales);
    }

    private static Setting number(String key, String path, int slot, Material material, double step, double min, double max) {
        return new Setting(key, path, slot, material, step, min, max, List.of());
    }
    private static Setting choice(String key, String path, int slot, Material material, List<String> choices) {
        return new Setting(key, path, slot, material, 0, 0, 0, choices);
    }
    private static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private int closeSlot;
        private final Map<Integer, Setting> settings = new LinkedHashMap<>();
        private final Map<String, Object> values = new LinkedHashMap<>();
        private final Map<String, Object> changed = new LinkedHashMap<>();
        @Override public Inventory getInventory() { return inventory; }
    }
}
