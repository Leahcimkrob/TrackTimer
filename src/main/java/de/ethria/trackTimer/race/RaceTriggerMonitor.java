package de.ethria.trackTimer.race;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Detects player and vehicle position changes once and forwards them to race trigger handlers. */
public final class RaceTriggerMonitor implements Listener {
    private final JavaPlugin plugin;
    private final List<BiConsumer<Player, Location>> handlers = new ArrayList<>();
    private final Map<UUID, BlockPosition> lastVehiclePositions = new HashMap<>();
    private final Map<UUID, BlockPosition> lastDispatchedPositions = new HashMap<>();
    private final BukkitTask vehicleMonitor;

    public RaceTriggerMonitor(JavaPlugin plugin) {
        this.plugin = plugin;
        vehicleMonitor = Bukkit.getScheduler().runTaskTimer(plugin, this::checkRidingPlayers, 1L, 1L);
    }

    public void addHandler(BiConsumer<Player, Location> handler) {
        handlers.add(handler);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() != null && event.hasChangedBlock()) dispatch(event.getPlayer(), event.getTo());
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (sameBlock(from, to)) return;
        for (var passenger : event.getVehicle().getPassengers()) {
            if (passenger instanceof Player player) {
                Location position = player.getLocation();
                position.setX(to.getX());
                position.setZ(to.getZ());
                dispatch(player, position);
            }
        }
    }

    private void checkRidingPlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getVehicle() == null) {
                lastVehiclePositions.remove(player.getUniqueId());
                continue;
            }
            Location position = player.getLocation();
            BlockPosition current = blockPosition(position);
            BlockPosition previous = lastVehiclePositions.put(player.getUniqueId(), current);
            if (!current.equals(previous)) dispatch(player, position);
        }
    }

    private boolean sameBlock(Location first, Location second) {
        return first.getBlockX() == second.getBlockX() && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    private void dispatch(Player player, Location position) {
        BlockPosition current = blockPosition(position);
        BlockPosition previous = lastDispatchedPositions.put(player.getUniqueId(), current);
        if (current.equals(previous)) return;
        for (BiConsumer<Player, Location> handler : handlers) handler.accept(player, position.clone());
    }

    private BlockPosition blockPosition(Location location) {
        return new BlockPosition(location.getWorld().getUID(), location.getBlockX(),
                location.getBlockY(), location.getBlockZ());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastVehiclePositions.remove(event.getPlayer().getUniqueId());
        lastDispatchedPositions.remove(event.getPlayer().getUniqueId());
    }

    public void shutdown() {
        vehicleMonitor.cancel();
        lastVehiclePositions.clear();
        lastDispatchedPositions.clear();
        handlers.clear();
    }

    private record BlockPosition(UUID worldId, int x, int y, int z) { }
}
