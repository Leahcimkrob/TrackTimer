package de.ethria.trackTimer.gui;

import de.ethria.trackTimer.database.DatabaseManager;
import de.ethria.trackTimer.heads.HeadDatabaseService;
import de.ethria.trackTimer.language.LanguageManager;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/** Shared plugin services and settings used by the separate editor GUIs. */
public final class EditorGuiContext {
    final JavaPlugin plugin;
    final DatabaseManager database;
    final LanguageManager language;
    final HeadDatabaseService heads;
    YamlConfiguration settings;
    YamlConfiguration triggerSettings;

    public EditorGuiContext(JavaPlugin plugin, DatabaseManager database, LanguageManager language, HeadDatabaseService heads) {
        this.plugin = plugin;
        this.database = database;
        this.language = language;
        this.heads = heads;
        reload();
    }

    public void reload() {
        File guiFolder = new File(plugin.getDataFolder(), "gui");
        if (!guiFolder.exists() && !guiFolder.mkdirs()) {
            plugin.getLogger().warning("Could not create GUI configuration folder: " + guiFolder);
        }
        settings = loadGuiConfig(guiFolder, "editor.yml");
        triggerSettings = loadGuiConfig(guiFolder, "trigger.yml");
    }

    private YamlConfiguration loadGuiConfig(File guiFolder, String fileName) {
        File file = new File(guiFolder, fileName);
        String resourcePath = "gui/" + fileName;
        if (!file.exists()) plugin.saveResource(resourcePath, false);
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        try (InputStream input = plugin.getResource(resourcePath)) {
            if (input == null) return configuration;
            try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(reader);
                configuration.setDefaults(defaults);
                configuration.options().copyDefaults(true);
                configuration.save(file);
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not merge default GUI settings in " + fileName + ": " + exception.getMessage());
        }
        return configuration;
    }

    ItemStack icon(String value) {
        if (value != null && value.startsWith("b64:")) {
            try {
                return ItemStack.deserializeBytes(Base64.getDecoder().decode(value.substring(4)));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Stored event icon data is invalid; using stone.");
            }
        }
        Material material = value == null ? null : Material.matchMaterial(value);
        if (material != null && material.isItem()) return new ItemStack(material);
        if (heads != null && value != null) {
            Optional<ItemStack> head = heads.head(value);
            if (head.isPresent()) return head.get();
        }
        return new ItemStack(Material.STONE);
    }

    String serializeIcon(ItemStack item) {
        return "b64:" + Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    ItemStack configuredItem(String path, Material fallback) {
        return configuredItem(settings, path, fallback);
    }

    ItemStack configuredItem(FileConfiguration config, String path, Material fallback) {
        return new ItemStack(material(config.getString(path + ".material"), fallback));
    }

    ItemStack configuredIcon(String path, Material fallback) {
        return configuredIcon(settings, path, fallback);
    }

    ItemStack configuredIcon(FileConfiguration config, String path, Material fallback) {
        String materialName = config.getString(path + ".material");
        Material configuredMaterial = materialName == null ? null : Material.matchMaterial(materialName);
        // A regular material explicitly configured by the server owner takes
        // precedence. PLAYER_HEAD is the marker used with HeadDatabase IDs.
        if (configuredMaterial != null && configuredMaterial.isItem()
                && configuredMaterial != Material.PLAYER_HEAD) {
            return new ItemStack(configuredMaterial);
        }

        String headId = config.getString(path + ".head-database-id");
        if (headId != null && heads != null) {
            Optional<ItemStack> head = heads.head(headId);
            if (head.isPresent()) return head.get();
        }
        String fallbackName = config.getString(path + ".fallback-material");
        if (fallbackName != null) return new ItemStack(material(fallbackName, fallback));
        if (configuredMaterial != null && configuredMaterial.isItem()) return new ItemStack(configuredMaterial);
        return configuredItem(config, path, fallback);
    }

    Material material(String name, Material fallback) {
        Material material = name == null ? null : Material.matchMaterial(name);
        return material != null && material.isItem() ? material : fallback;
    }

    int slot(String path, int fallback, int size) {
        return slot(settings, path, fallback, size);
    }

    int slot(FileConfiguration config, String path, int fallback, int size) {
        int slot = config.getInt(path, fallback);
        return slot >= 0 && slot < size ? slot : fallback;
    }

    int inventorySize(String path, int fallback) {
        return inventorySize(settings, path, fallback, "editor.yml");
    }

    int inventorySize(FileConfiguration config, String path, int fallback, String fileName) {
        int configured = config.getInt(path, fallback);
        if (configured >= 9 && configured <= 54 && configured % 9 == 0) return configured;
        plugin.getLogger().warning("Invalid GUI inventory size at '" + path + "': " + configured
                + ". Resetting to " + fallback + " (valid sizes are 9 through 54 in rows of 9).");
        config.set(path, fallback);
        try {
            config.save(new File(new File(plugin.getDataFolder(), "gui"), fileName));
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save corrected GUI size: " + exception.getMessage());
        }
        return fallback;
    }
}
