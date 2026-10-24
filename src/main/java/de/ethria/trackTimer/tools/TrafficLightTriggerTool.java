package de.ethria.trackTimer.tools;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import de.ethria.trackTimer.database.DatabaseManager;
import de.ethria.trackTimer.database.DatabaseManager.TrafficLightTrigger;
import de.ethria.trackTimer.gui.EditorGuiContext;
import de.ethria.trackTimer.gui.EventEditorGui;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Skull;
import org.bukkit.block.data.Rotatable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Places and protects the five dark heads used by an event's traffic-light start. */
public final class TrafficLightTriggerTool implements Listener {
    private static final int HEAD_COUNT = 5;
    private static final String DARK_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjRhYWM4ZTcxMzczNjcyOWM2ODA3MWIyNDlkMzNmYmQ3MWEwYTNjZWQwMzIxZTkyOGY5NGJjOTNjMGU5NDZmNyJ9fX0=";
    private static final String TOOL_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNmY5YzZjMzM5OWZlMTBlODgyOTM5NmZkNmJlYTM2ZDIwMTZhZmFkZWUzOGQxM2FlY2I2Zjg5MTg0NjdmNjQ4ZSJ9fX0=";

    private static final Map<UUID, TrafficLightTriggerTool> ACTIVE_TOOLS = new ConcurrentHashMap<>();
    private final EditorGuiContext context;
    private final EventEditorGui eventEditorGui;
    private final NamespacedKey toolKey;
    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();
    private final Set<String> protectedBlocks = ConcurrentHashMap.newKeySet();

    public TrafficLightTriggerTool(EditorGuiContext context, EventEditorGui eventEditorGui) {
        this.context = context;
        this.eventEditorGui = eventEditorGui;
        toolKey = new NamespacedKey(context.plugin(), "traffic_light_trigger_tool");
        refreshProtectedBlocks();
    }

    public static ItemStack trafficLightHead() {
        return head(TOOL_TEXTURE);
    }

    public void begin(Player player, long eventId, int overviewPage) {
        StartTriggerTool.cancelActive(player);
        TrafficLightTriggerTool active = ACTIVE_TOOLS.get(player.getUniqueId());
        if (active != null) active.stop(player, true);
        stop(player, false);

        ItemStack tool = new ItemStack(context.material(
                context.triggerSettings().getString("guis.trigger-editor.items.tool.material"), Material.STICK));
        ItemMeta meta = tool.getItemMeta();
        meta.displayName(context.language().gui("trigger-editor.tool.traffic-light.name"));
        List<Component> lore = new ArrayList<>(context.language().guiList("trigger-editor.tool.lore"));
        lore.addAll(context.language().guiList("trigger-editor.tool.traffic-light.lore"));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.BYTE, (byte) 1);
        tool.setItemMeta(meta);
        player.getInventory().addItem(tool);

        Selection selection = new Selection(eventId, overviewPage);
        selections.put(player.getUniqueId(), selection);
        ACTIVE_TOOLS.put(player.getUniqueId(), this);
        selection.particles = Bukkit.getScheduler().runTaskTimer(context.plugin(), () -> showLights(player, selection), 0L, 10L);
        player.sendMessage(context.language().chat("trigger.traffic-light-selection-started"));
    }

    public static void cancelActive(Player player) {
        TrafficLightTriggerTool active = ACTIVE_TOOLS.get(player.getUniqueId());
        if (active != null) active.stop(player, true);
    }

    @EventHandler
    public void onBlockInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isTool(event.getItem()) || ACTIVE_TOOLS.get(player.getUniqueId()) != this) return;
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block clicked = event.getClickedBlock();
        Selection selection = selections.get(player.getUniqueId());
        if (clicked == null || selection == null) return;
        event.setCancelled(true);
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) add(player, selection, clicked);
        else remove(player, selection, clicked);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (protectedBlocks.contains(key(event.getBlock().getLocation()))) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> protectedBlocks.contains(key(block.getLocation())));
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> protectedBlocks.contains(key(block.getLocation())));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> protectedBlocks.contains(key(block.getLocation())))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> protectedBlocks.contains(key(block.getLocation())))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onSelectionChat(AsyncChatEvent event) {
        if (!"exit".equalsIgnoreCase(PlainTextComponentSerializer.plainText().serialize(event.message()).trim())) return;
        Selection selection = selections.get(event.getPlayer().getUniqueId());
        if (selection == null || ACTIVE_TOOLS.get(event.getPlayer().getUniqueId()) != this) return;
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(context.plugin(), () -> {
            Player player = event.getPlayer();
            stop(player, true);
            player.sendMessage(context.language().chat("trigger.traffic-light-selection-ended"));
            eventEditorGui.reopen(player, selection.eventId, selection.overviewPage);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stop(event.getPlayer(), false);
    }

    private void add(Player player, Selection selection, Block anchor) {
        BlockFace towardPlayer = facingToward(player.getLocation(), anchor.getLocation());
        BlockFace side = towardPlayer == BlockFace.NORTH || towardPlayer == BlockFace.SOUTH
                ? BlockFace.EAST : BlockFace.NORTH;
        List<Block> heads = headBlocks(anchor, towardPlayer, side);
        try {
            for (TrafficLightTrigger light : context.database().listTrafficLightTriggers(selection.eventId)) {
                if (!light.server().equalsIgnoreCase(context.database().getServerName())) continue;
                if (sameAnchor(light, anchor)) {
                    player.sendMessage(context.language().chat("trigger.already-exists"));
                    return;
                }
                if (overlaps(light, heads)) {
                    player.sendMessage(context.language().chat("trigger.traffic-light-space-occupied"));
                    return;
                }
            }
            for (Block block : heads) {
                if (!block.isEmpty()) {
                    player.sendMessage(context.language().chat("trigger.traffic-light-space-occupied"));
                    return;
                }
            }
            boolean added = context.database().addTrafficLightTrigger(selection.eventId,
                    context.database().getServerName(), anchor.getWorld().getName(),
                    anchor.getX(), anchor.getY(), anchor.getZ(), towardPlayer.name());
            if (!added) {
                player.sendMessage(context.language().chat("trigger.already-exists"));
                return;
            }
            // A Rotatable head's rotation points toward its back; the texture's
            // visible face is therefore opposite the side the player is on.
            for (Block block : heads) setHead(block, DARK_TEXTURE, towardPlayer.getOppositeFace());
            refreshProtectedBlocks();
            player.sendMessage(context.language().chat("trigger.traffic-light-added"));
        } catch (SQLException exception) {
            context.plugin().getLogger().log(Level.SEVERE, "Could not save traffic-light trigger.", exception);
            player.sendMessage(context.language().chat("event.list-failed"));
        }
    }

    private void remove(Player player, Selection selection, Block clicked) {
        try {
            for (TrafficLightTrigger light : context.database().listTrafficLightTriggers(selection.eventId)) {
                if (!light.server().equalsIgnoreCase(context.database().getServerName())) continue;
                List<Block> heads = headBlocks(light);
                boolean isHead = heads.stream().anyMatch(block -> block.getLocation().equals(clicked.getLocation()));
                if (!isHead) continue;
                if (context.database().removeTrafficLightTrigger(selection.eventId, light.server(), light.world(),
                        light.x(), light.y(), light.z())) {
                    for (Block block : heads) {
                        if (block.getType() == Material.PLAYER_HEAD || block.getType() == Material.PLAYER_WALL_HEAD) {
                            block.setType(Material.AIR, false);
                        }
                    }
                    refreshProtectedBlocks();
                    player.sendMessage(context.language().chat("trigger.removed"));
                }
                return;
            }
            player.sendMessage(context.language().chat("trigger.not-found"));
        } catch (SQLException exception) {
            context.plugin().getLogger().log(Level.SEVERE, "Could not remove traffic-light trigger.", exception);
            player.sendMessage(context.language().chat("event.list-failed"));
        }
    }

    private void showLights(Player player, Selection selection) {
        if (!player.isOnline() || selections.get(player.getUniqueId()) != selection) return;
        try {
            for (TrafficLightTrigger light : context.database().listTrafficLightTriggers(selection.eventId)) {
                if (!light.server().equalsIgnoreCase(context.database().getServerName())
                        || !light.world().equals(player.getWorld().getName())) continue;
                for (Block block : headBlocks(light)) {
                    player.spawnParticle(org.bukkit.Particle.FLAME,
                            new Location(player.getWorld(), block.getX() + .5, block.getY() + .7, block.getZ() + .5),
                            1, .08, .08, .08, 0);
                }
            }
        } catch (SQLException exception) {
            context.plugin().getLogger().log(Level.WARNING, "Could not show traffic-light markers.", exception);
        }
    }

    private void refreshProtectedBlocks() {
        protectedBlocks.clear();
        try {
            for (TrafficLightTrigger light : context.database().listTrafficLightTriggers(context.database().getServerName())) {
                for (Block block : headBlocks(light)) protectedBlocks.add(key(block.getLocation()));
            }
        } catch (SQLException exception) {
            context.plugin().getLogger().log(Level.SEVERE, "Could not load traffic-light block protection.", exception);
        }
    }

    private List<Block> headBlocks(TrafficLightTrigger light) {
        org.bukkit.World world = Bukkit.getWorld(light.world());
        if (world == null) return List.of();
        Block anchor = world.getBlockAt(light.x(), light.y(), light.z());
        BlockFace face;
        try { face = BlockFace.valueOf(light.facing()); }
        catch (IllegalArgumentException exception) { face = BlockFace.SOUTH; }
        BlockFace side = face == BlockFace.NORTH || face == BlockFace.SOUTH ? BlockFace.EAST : BlockFace.NORTH;
        return headBlocks(anchor, face, side);
    }

    /** Returns each saved five-head row ordered from the driver's left to right. */
    public List<List<SignalHead>> signalHeadRows(long eventId, String server, String worldName) throws SQLException {
        List<List<SignalHead>> rows = new ArrayList<>();
        for (TrafficLightTrigger light : context.database().listTrafficLightTriggers(eventId)) {
            if (!light.server().equalsIgnoreCase(server) || !light.world().equals(worldName)) continue;
            BlockFace face;
            try { face = BlockFace.valueOf(light.facing()); }
            catch (IllegalArgumentException exception) { face = BlockFace.SOUTH; }
            List<Block> blocks = headBlocks(light);
            if (face == BlockFace.NORTH || face == BlockFace.WEST) Collections.reverse(blocks);
            BlockFace rotation = face.getOppositeFace();
            List<SignalHead> row = blocks.stream()
                    .map(block -> new SignalHead(block, rotation))
                    .toList();
            if (row.size() == HEAD_COUNT) rows.add(row);
        }
        return List.copyOf(rows);
    }

    public record SignalHead(Block block, BlockFace rotation) { }

    private List<Block> headBlocks(Block anchor, BlockFace face, BlockFace side) {
        Block center = anchor.getRelative(face);
        List<Block> blocks = new ArrayList<>(HEAD_COUNT);
        for (int offset = -2; offset <= 2; offset++) {
            blocks.add(center.getRelative(side, offset));
        }
        return blocks;
    }

    private boolean overlaps(TrafficLightTrigger light, List<Block> proposed) {
        Set<String> positions = new HashSet<>();
        for (Block block : proposed) positions.add(key(block.getLocation()));
        return headBlocks(light).stream().anyMatch(block -> positions.contains(key(block.getLocation())));
    }

    private boolean sameAnchor(TrafficLightTrigger light, Block block) {
        return light.server().equalsIgnoreCase(context.database().getServerName())
                && light.world().equals(block.getWorld().getName())
                && light.x() == block.getX() && light.y() == block.getY() && light.z() == block.getZ();
    }

    private static BlockFace facingToward(Location player, Location block) {
        double dx = player.getX() - (block.getBlockX() + .5);
        double dz = player.getZ() - (block.getBlockZ() + .5);
        if (Math.abs(dx) > Math.abs(dz)) return dx >= 0 ? BlockFace.EAST : BlockFace.WEST;
        return dz >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    private static void setHead(Block block, String texture, BlockFace facing) {
        block.setType(Material.PLAYER_HEAD, false);
        if (block.getBlockData() instanceof Rotatable data) {
            data.setRotation(facing);
            block.setBlockData(data, false);
        }
        setHeadTexture(block, texture, facing);
    }

    public static void setHeadTexture(Block block, String texture, BlockFace facing) {
        if (block.getType() != Material.PLAYER_HEAD && block.getType() != Material.PLAYER_WALL_HEAD) return;
        if (block.getState() instanceof Skull skull) {
            PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), "TrackTimer");
            profile.setProperty(new ProfileProperty("textures", texture));
            skull.setPlayerProfile(profile);
            skull.setRotation(facing);
            skull.update(true, false);
        }
    }

    private static ItemStack head(String texture) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (item.getItemMeta() instanceof SkullMeta meta) {
            PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), "TrackTimer");
            profile.setProperty(new ProfileProperty("textures", texture));
            meta.setPlayerProfile(profile);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String key(Location location) {
        return location.getWorld().getName() + ':' + location.getBlockX() + ':' + location.getBlockY() + ':' + location.getBlockZ();
    }

    private boolean isTool(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(toolKey, PersistentDataType.BYTE);
    }

    private void stop(Player player, boolean removeTool) {
        Selection selection = selections.remove(player.getUniqueId());
        ACTIVE_TOOLS.remove(player.getUniqueId(), this);
        if (selection != null && selection.particles != null) selection.particles.cancel();
        if (!removeTool) return;
        for (ItemStack item : player.getInventory().getContents()) if (isTool(item)) player.getInventory().remove(item);
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
