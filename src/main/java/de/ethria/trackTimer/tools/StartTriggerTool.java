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
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Handles selection, persistence, and visual feedback for start triggers. */
public final class StartTriggerTool implements Listener {
    private final EditorGuiContext context;
    private final EventEditorGui eventEditorGui;
    private final NamespacedKey toolKey;
    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();

    public StartTriggerTool(EditorGuiContext context, EventEditorGui eventEditorGui) {
        this.context = context;
        this.eventEditorGui = eventEditorGui;
        this.toolKey = new NamespacedKey(context.plugin(), "start_trigger_tool");
    }

    public void begin(Player player, long eventId, int overviewPage) {
        stop(player, false);
        ItemStack tool = new ItemStack(context.material(
                context.triggerSettings().getString("guis.trigger-editor.items.tool.material"), Material.STICK));
        ItemMeta meta = tool.getItemMeta();
        meta.displayName(context.language().gui("trigger-editor.tool.name"));
        meta.lore(context.language().guiList("trigger-editor.tool.lore"));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.BYTE, (byte) 1);
        tool.setItemMeta(meta);
        player.getInventory().addItem(tool);

        Selection selection = new Selection(eventId, overviewPage);
        selections.put(player.getUniqueId(), selection);
        selection.particles = Bukkit.getScheduler().runTaskTimer(context.plugin(), () -> showStartParticles(player, selection), 0L, 10L);
        player.sendMessage(context.language().chat("trigger.selection-started"));
    }

    private void showStartParticles(Player player, Selection selection) {
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
                if (!"start".equals(trigger.type()) || !trigger.world().equals(player.getWorld().getName())) continue;
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
        Selection selection = selections.get(event.getPlayer().getUniqueId());
        if (selection == null || (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        event.setCancelled(true);
        Block block = event.getClickedBlock();
        if (block == null) return;

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) addStartTrigger(event.getPlayer(), selection, block);
        else removeStartTrigger(event.getPlayer(), selection, block);
    }

    private void addStartTrigger(Player player, Selection selection, Block block) {
        if (isPressurePlate(block) && (!(block.getBlockData() instanceof Powerable powerable) || !powerable.isPowered())) {
            player.sendMessage(context.language().chat("trigger.pressure-plate-not-pressed"));
            return;
        }
        try {
            context.database().addStartTrigger(selection.eventId, context.plugin().getServer().getName(),
                    block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), block.getType().name());
            player.sendMessage(context.language().chat("trigger.start-added"));
        } catch (SQLException exception) {
            context.plugin().getLogger().log(Level.SEVERE, "Could not save start trigger.", exception);
            player.sendMessage(context.language().chat("event.list-failed"));
        }
    }

    private void removeStartTrigger(Player player, Selection selection, Block block) {
        try {
            boolean removed = context.database().removeStartTrigger(selection.eventId,
                    context.plugin().getServer().getName(), block.getWorld().getName(),
                    block.getX(), block.getY(), block.getZ());
            player.sendMessage(context.language().chat(removed ? "trigger.removed" : "trigger.not-found"));
        } catch (SQLException exception) {
            context.plugin().getLogger().log(Level.SEVERE, "Could not remove start trigger.", exception);
            player.sendMessage(context.language().chat("event.list-failed"));
        }
    }

    @EventHandler
    public void onSelectionChat(AsyncChatEvent event) {
        if (!"exit".equalsIgnoreCase(PlainTextComponentSerializer.plainText().serialize(event.message()).trim())) return;
        Selection selection = selections.get(event.getPlayer().getUniqueId());
        if (selection == null) return;
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(context.plugin(), () -> {
            Player player = event.getPlayer();
            stop(player, true);
            player.sendMessage(context.language().chat("trigger.selection-ended"));
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

    private void stop(Player player, boolean removeTool) {
        Selection selection = selections.remove(player.getUniqueId());
        if (selection != null && selection.particles != null) selection.particles.cancel();
        if (!removeTool) return;
        for (ItemStack item : player.getInventory().getContents()) {
            if (isTool(item)) player.getInventory().remove(item);
        }
    }

    private static final class Selection {
        private final long eventId;
        private final int overviewPage;
        private BukkitTask particles;

        private Selection(long eventId, int overviewPage) {
            this.eventId = eventId;
            this.overviewPage = overviewPage;
        }
    }
}
