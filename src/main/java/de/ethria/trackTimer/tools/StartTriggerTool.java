package de.ethria.trackTimer.tools;

import de.ethria.trackTimer.database.DatabaseManager;
import de.ethria.trackTimer.database.DatabaseManager.EventTrigger;
import de.ethria.trackTimer.gui.EditorGuiContext;
import de.ethria.trackTimer.gui.EventEditorGui;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.data.Powerable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;

import java.sql.SQLException;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Handles block-trigger selection, persistence, and visual feedback. */
public class StartTriggerTool implements Listener {
    private static final Map<UUID, StartTriggerTool> ACTIVE_TOOLS = new ConcurrentHashMap<>();
    private final EditorGuiContext context;
    private final EventEditorGui eventEditorGui;
    private final String triggerType;
    private final String configuredMode;
    private final String toolVariant;
    private final NamespacedKey toolKey;
    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();

    public StartTriggerTool(EditorGuiContext context, EventEditorGui eventEditorGui) {
        this(context, eventEditorGui, "start");
    }

    protected StartTriggerTool(EditorGuiContext context, EventEditorGui eventEditorGui, String triggerType) {
        this(context, eventEditorGui, triggerType, "BLOCK", triggerType);
    }

    protected StartTriggerTool(EditorGuiContext context, EventEditorGui eventEditorGui, String triggerType,
                               String configuredMode, String toolVariant) {
        this.context = context;
        this.eventEditorGui = eventEditorGui;
        this.triggerType = triggerType;
        this.configuredMode = configuredMode;
        this.toolVariant = toolVariant;
        this.toolKey = new NamespacedKey(context.plugin(), toolVariant + "_trigger_tool");
    }

    public void begin(Player player, long eventId, int overviewPage) {
        beginWithOrder(player, eventId, overviewPage, null);
    }

    protected void beginWithOrder(Player player, long eventId, int overviewPage, Integer checkpointOrder) {
        StartTriggerTool currentTool = ACTIVE_TOOLS.get(player.getUniqueId());
        if (currentTool != null) currentTool.stop(player, true);
        stop(player, false);
        ItemStack tool = new ItemStack(context.material(
                context.triggerSettings().getString("guis.trigger-editor.items.tool.material"), Material.STICK));
        ItemMeta meta = tool.getItemMeta();
        meta.displayName(context.language().gui("trigger-editor.tool." + toolVariant + ".name"));
        List<Component> lore = new ArrayList<>(context.language().guiList("trigger-editor.tool.lore"));
        lore.addAll(context.language().guiList("trigger-editor.tool." + toolVariant + ".lore"));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.BYTE, (byte) 1);
        tool.setItemMeta(meta);
        player.getInventory().addItem(tool);

        Selection selection = new Selection(eventId, overviewPage, checkpointOrder);
        selections.put(player.getUniqueId(), selection);
        ACTIVE_TOOLS.put(player.getUniqueId(), this);
        selection.particles = Bukkit.getScheduler().runTaskTimer(context.plugin(), () -> showTriggerParticles(player, selection), 0L, 10L);
        player.sendMessage(context.language().chat("trigger." + toolVariant + "-selection-started"));
    }

    private void showTriggerParticles(Player player, Selection selection) {
        if (!player.isOnline() || selections.get(player.getUniqueId()) != selection) return;
        try {
            Particle particle;
            try {
                particle = Particle.valueOf(context.triggerSettings().getString(
                        "guis.trigger-editor.items.tool.particle", "FLAME").toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                particle = Particle.FLAME;
            }
            for (EventTrigger trigger : context.database().listEventTriggerDetails(selection.eventId)) {
                if (!triggerType.equals(trigger.type()) || !trigger.world().equals(player.getWorld().getName())) continue;
                if ("redstone".equals(toolVariant) && !"REDSTONE_SIGNAL".equals(trigger.triggerMode())) continue;
                Location point = new Location(player.getWorld(), trigger.x() + .5, trigger.y() + 1.1, trigger.z() + .5);
                player.spawnParticle(particle, point, 2, .12, .12, .12, 0);
            }
        } catch (SQLException exception) {
            context.plugin().getLogger().warning("Could not show start trigger particles: " + exception.getMessage());
        }
    }

    @EventHandler
    public void onBlockInteract(PlayerInteractEvent event) {
        if (!isTool(event.getItem())) return;
        if (ACTIVE_TOOLS.get(event.getPlayer().getUniqueId()) != this) return;
        Selection selection = selections.get(event.getPlayer().getUniqueId());
        if (selection == null || (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        event.setCancelled(true);
        Block block = event.getClickedBlock();
        if (block == null) return;

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) addTrigger(event.getPlayer(), selection, block);
        else removeTrigger(event.getPlayer(), selection, block);
    }

    private void addTrigger(Player player, Selection selection, Block block) {
        if (!"REDSTONE_SIGNAL".equals(configuredMode) && isPressurePlate(block)
                && (!(block.getBlockData() instanceof Powerable powerable) || !powerable.isPowered())) {
            player.sendMessage(context.language().chat("trigger.pressure-plate-not-pressed"));
            return;
        }
        try {
            String mode = "REDSTONE_SIGNAL".equals(configuredMode) ? configuredMode
                    : isPressurePlate(block) ? "PRESSURE_PLATE" : configuredMode;
            boolean added = addTriggerToDatabase(selection.eventId, context.plugin().getServer().getName(),
                    block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), block.getType().name(),
                    mode, selection.checkpointOrder);
            if ("checkpoint".equals(triggerType)) {
                player.sendMessage(context.language().chat(added
                                ? "trigger.checkpoint-added" : "trigger.checkpoint-order-used",
                        de.ethria.trackTimer.language.LanguageManager.placeholders("number", selection.checkpointOrder)));
                return;
            }
            player.sendMessage(context.language().chat(added
                    ? "trigger." + toolVariant + "-added" : "trigger.already-exists"));
        } catch (SQLException exception) {
            context.plugin().getLogger().log(Level.SEVERE, "Could not save " + triggerType + " trigger.", exception);
            player.sendMessage(context.language().chat("event.list-failed"));
        }
    }

    private void removeTrigger(Player player, Selection selection, Block block) {
        try {
            boolean removed = removeTriggerFromDatabase(selection.eventId,
                    context.plugin().getServer().getName(), block.getWorld().getName(),
                    block.getX(), block.getY(), block.getZ());
            player.sendMessage(context.language().chat(removed ? "trigger.removed" : "trigger.not-found"));
        } catch (SQLException exception) {
            context.plugin().getLogger().log(Level.SEVERE, "Could not remove " + triggerType + " trigger.", exception);
            player.sendMessage(context.language().chat("event.list-failed"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSelectionChat(AsyncChatEvent event) {
        if (!"exit".equalsIgnoreCase(
                PlainTextComponentSerializer.plainText().serialize(event.message()).trim())) return;
        Selection selection = selections.get(event.getPlayer().getUniqueId());
        if (selection == null || ACTIVE_TOOLS.get(event.getPlayer().getUniqueId()) != this) return;
        // Cancel at HIGHEST priority so exit is consumed even if another listener handles chat.
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(context.plugin(), () -> {
            Player player = event.getPlayer();
            stop(player, true);
            player.sendMessage(context.language().chat("trigger." + toolVariant + "-selection-ended"));
            eventEditorGui.reopen(player, selection.eventId, selection.overviewPage);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stop(event.getPlayer(), false);
    }

    private boolean isTool(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(toolKey, PersistentDataType.BYTE);
    }

    private boolean isPressurePlate(Block block) {
        return block.getType().name().endsWith("_PRESSURE_PLATE");
    }

    private boolean addTriggerToDatabase(long eventId, String server, String world, int x, int y, int z,
                                         String blockType, String triggerMode, Integer checkpointOrder)
            throws SQLException {
        if ("REDSTONE_SIGNAL".equals(triggerMode)) {
            return context.database().addRedstoneStartTrigger(eventId, server, world, x, y, z, blockType);
        }
        if ("end".equals(triggerType)) {
            return context.database().addEndTrigger(eventId, server, world, x, y, z, blockType, triggerMode);
        }
        if ("checkpoint".equals(triggerType)) {
            return context.database().addCheckpointTrigger(eventId, checkpointOrder, server, world, x, y, z,
                    blockType, triggerMode);
        }
        return context.database().addStartTrigger(eventId, server, world, x, y, z, blockType, triggerMode);
    }

    private boolean removeTriggerFromDatabase(long eventId, String server, String world, int x, int y, int z)
            throws SQLException {
        if ("REDSTONE_SIGNAL".equals(configuredMode)) return context.database().removeRedstoneStartTrigger(eventId, server, world, x, y, z);
        if ("end".equals(triggerType)) return context.database().removeEndTrigger(eventId, server, world, x, y, z);
        if ("checkpoint".equals(triggerType)) return context.database().removeCheckpointTrigger(eventId, server, world, x, y, z);
        return context.database().removeStartTrigger(eventId, server, world, x, y, z);
    }

    private void stop(Player player, boolean removeTool) {
        Selection selection = selections.remove(player.getUniqueId());
        ACTIVE_TOOLS.remove(player.getUniqueId(), this);
        if (selection != null && selection.particles != null) selection.particles.cancel();
        if (!removeTool) return;
        for (ItemStack item : player.getInventory().getContents()) {
            if (isTool(item)) player.getInventory().remove(item);
        }
    }

    private static final class Selection {
        private final long eventId;
        private final int overviewPage;
        private final Integer checkpointOrder;
        private BukkitTask particles;

        private Selection(long eventId, int overviewPage, Integer checkpointOrder) {
            this.eventId = eventId;
            this.overviewPage = overviewPage;
            this.checkpointOrder = checkpointOrder;
        }
    }
}
