package de.ethria.trackTimer.language;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;

/**
 * Loads chat and GUI messages for a configured locale (e.g. "de-DE") and
 * falls back to English ("en-US") when a key or file is missing. Messages
 * are authored using MiniMessage (https://docs.papermc.io/adventure/minimessage/api/).
 */
public final class LanguageManager {

    private static final String DEFAULT_LOCALE = "en-US";

    private final JavaPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private String locale;

    private YamlConfiguration chatMessages;
    private YamlConfiguration guiMessages;
    private YamlConfiguration fallbackChatMessages;
    private YamlConfiguration fallbackGuiMessages;

    public LanguageManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Builds a {@link TagResolver} from alternating key/value pairs that can
     * be used as MiniMessage placeholders, e.g. {@code <event>} for the
     * pair {@code "event", eventName}. Values are inserted as plain text and
     * are not parsed as MiniMessage, so player-controlled input cannot
     * inject formatting tags.
     */
    public static TagResolver placeholders(Object... pairs) {
        if (pairs.length == 0) {
            return TagResolver.empty();
        }
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("Placeholders must be provided as key/value pairs.");
        }
        TagResolver.Builder builder = TagResolver.builder();
        for (int i = 0; i < pairs.length; i += 2) {
            String key = String.valueOf(pairs[i]);
            String value = String.valueOf(pairs[i + 1]);
            builder.resolver(Placeholder.unparsed(key, value));
        }
        return builder.build();
    }

    public void load() {
        this.locale = plugin.getConfig().getString("language", DEFAULT_LOCALE);

        fallbackChatMessages = loadLanguageFile(DEFAULT_LOCALE + ".yml");
        fallbackGuiMessages = loadLanguageFile(DEFAULT_LOCALE + "_gui.yml");

        if (locale.equalsIgnoreCase(DEFAULT_LOCALE)) {
            chatMessages = fallbackChatMessages;
            guiMessages = fallbackGuiMessages;
        } else {
            chatMessages = loadLanguageFile(locale + ".yml");
            guiMessages = loadLanguageFile(locale + "_gui.yml");
        }
    }

    /**
     * Resolves a chat message by key (dot-path, e.g. "event.created"),
     * prefixes it with the configured prefix and applies MiniMessage
     * placeholders/tags.
     */
    public Component chat(String key, TagResolver... resolvers) {
        String prefix = resolveRaw(chatMessages, fallbackChatMessages, "prefix", "");
        String message = resolveRaw(chatMessages, fallbackChatMessages, key, key);
        return miniMessage.deserialize(prefix + message, resolvers);
    }

    /** Resolves a chat message without the configured prefix, for inline chat components. */
    public Component chatFragment(String key, TagResolver... resolvers) {
        String message = resolveRaw(chatMessages, fallbackChatMessages, key, key);
        return miniMessage.deserialize(message, resolvers);
    }

    /**
     * Resolves a GUI message by key without the chat prefix, e.g. for
     * inventory titles and item display names.
     */
    public Component gui(String key, TagResolver... resolvers) {
        String message = resolveRaw(guiMessages, fallbackGuiMessages, key, key);
        return miniMessage.deserialize(message, resolvers);
    }

    /**
     * Resolves a GUI message that contains a list of lines, e.g. item lore.
     */
    public List<Component> guiList(String key, TagResolver... resolvers) {
        List<String> lines = resolveList(guiMessages, fallbackGuiMessages, key);
        return lines.stream()
                .map(line -> (Component) miniMessage.deserialize(line, resolvers))
                .toList();
    }

    private String resolveRaw(YamlConfiguration primary, YamlConfiguration fallback, String key, String defaultValue) {
        if (primary != null) {
            String value = primary.getString(key);
            if (value != null) {
                return value;
            }
        }
        if (fallback != null) {
            String value = fallback.getString(key);
            if (value != null) {
                return value;
            }
        }
        return defaultValue;
    }

    private List<String> resolveList(YamlConfiguration primary, YamlConfiguration fallback, String key) {
        if (primary != null && primary.isList(key)) {
            return primary.getStringList(key);
        }
        if (fallback != null && fallback.isList(key)) {
            return fallback.getStringList(key);
        }
        return List.of();
    }

    private YamlConfiguration loadLanguageFile(String fileName) {
        File dataFolder = new File(plugin.getDataFolder(), "language");
        File file = new File(dataFolder, fileName);

        if (!file.exists()) {
            saveResourceIfPresent(fileName, file, dataFolder);
        }

        if (file.exists()) {
            YamlConfiguration messages = YamlConfiguration.loadConfiguration(file);
            mergeBundledDefaults(fileName, file, messages);
            return messages;
        }

        return loadFromClasspath(fileName);
    }

    private void mergeBundledDefaults(String fileName, File file, YamlConfiguration messages) {
        YamlConfiguration defaults = loadFromClasspath(fileName);
        messages.setDefaults(defaults);
        messages.options().copyDefaults(true);
        try {
            messages.save(file);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not add new default language keys to " + fileName, exception);
        }
    }

    private void saveResourceIfPresent(String fileName, File file, File dataFolder) {
        String resourcePath = "language/" + fileName;
        if (plugin.getResource(resourcePath) == null) {
            return;
        }
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            plugin.getLogger().warning("Could not create language folder: " + dataFolder);
            return;
        }
        plugin.saveResource(resourcePath, false);
    }

    private YamlConfiguration loadFromClasspath(String fileName) {
        String resourcePath = "language/" + fileName;
        try (InputStream input = plugin.getResource(resourcePath)) {
            if (input == null) {
                plugin.getLogger().warning("Language file not found: " + resourcePath);
                return new YamlConfiguration();
            }
            try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not load language file: " + resourcePath, exception);
            return new YamlConfiguration();
        }
    }

    public String getLocale() {
        return locale;
    }
}

