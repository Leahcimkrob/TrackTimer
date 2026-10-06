package de.ethria.trackTimer.race;

import org.bukkit.Location;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** Measures CMI's generated display once it exists, then anchors its upper edge. */
final class CmiHologramTopAligner {
    private static final class Pending {
        final Object hologram;
        final double topY;
        Location location;
        CompletableFuture<?> update;
        int corrections;

        Pending(Object hologram, Location location, CompletableFuture<?> update) {
            this.hologram = hologram;
            this.topY = location.getY();
            this.location = location.clone();
            this.update = update;
        }
    }
    private final JavaPlugin plugin;
    private final Map<String, Pending> pending = new HashMap<>();
    private BukkitTask task;

    CmiHologramTopAligner(JavaPlugin plugin) { this.plugin = plugin; }

    void align(String name, Object hologram, Location topLocation) throws ReflectiveOperationException {
        CompletableFuture<?> update = (CompletableFuture<?>) call(hologram, "requestFullUpdate");
        pending.put(name, new Pending(hologram, topLocation, update));
        if (task == null) task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::alignPending, 1L, 20L);
    }

    void remove(String name) { pending.remove(name); }

    private void alignPending() {
        pending.entrySet().removeIf(entry -> {
            try {
                return align(entry.getValue());
            } catch (ReflectiveOperationException | RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING, "Could not align CMI hologram '" + entry.getKey() + "' to its top edge.", exception);
                return true;
            }
        });
        if (pending.isEmpty()) {
            task.cancel();
            task = null;
        }
    }

    private boolean align(Pending pending) throws ReflectiveOperationException {
        // CMI builds/replaces its text displays asynchronously. Measuring before
        // this completes can use the previous head setting or text scale.
        if (!pending.update.isDone()) return false;
        pending.update.join();
        Object hologram = pending.hologram;
        Object viewers = call(hologram, "getPlayersFromVisibilityRange");
        if (!(viewers instanceof Iterable<?> ids)) return false;
        for (Object id : ids) {
            if (!(id instanceof UUID uuid)) continue;
            Object data = hologram.getClass().getMethod("getData", UUID.class).invoke(hologram, uuid);
            Object batch = call(data, "getHologramBatch");
            if (batch == null) continue;
            // Only modern text displays provide scale-independent geometric bounds.
            if (!batch.getClass().getSimpleName().equals("CMIHologramBatchDisplay")) continue;
            Object display = call(batch, "getFrontDisplay");
            if (display == null || !(call(display, "getDisplayEntity") instanceof TextDisplay text)) continue;
            Transformation transform = (Transformation) call(display, "getTransformation");
            Location origin = (Location) call(display, "getLocation");
            int lines = text.getText().split("\\n", -1).length;
            float height = lines * 10 / 40f;
            Matrix4f matrix = new Matrix4f().translation(transform.getTranslation())
                    .rotate(transform.getLeftRotation()).scale(transform.getScale()).rotate(transform.getRightRotation());
            // Minecraft text grows upwards from the display origin. Transform
            // both edges; fixed upright boards do not rotate text around X/Z.
            float bottom = matrix.transformPosition(new Vector3f(0, 0, 0)).y;
            float top = matrix.transformPosition(new Vector3f(0, height, 0)).y;
            double shift = pending.topY - (origin.getY() + Math.max(bottom, top));
            if (Math.abs(shift) > 0.001) {
                if (++pending.corrections > 4) {
                    throw new IllegalStateException("CMI display did not settle at the requested top edge.");
                }
                Location location = pending.location.clone();
                location.add(0, shift, 0);
                hologram.getClass().getMethod("setLocation", Location.class).invoke(hologram, location);
                pending.location = location;
                pending.update = (CompletableFuture<?>) call(hologram, "requestFullUpdate");
                return false;
            }
            return true;
        }
        return false;
    }

    private Object call(Object instance, String method) throws ReflectiveOperationException {
        return instance.getClass().getMethod(method).invoke(instance);
    }
}
