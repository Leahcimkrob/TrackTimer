package de.ethria.trackTimer.race;

import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

/** Shared vertical tolerance rules for block based race triggers. */
public final class TriggerPositionMatcher {
    private TriggerPositionMatcher() { }

    public static double heightTolerance(JavaPlugin plugin) {
        double tolerance = plugin.getConfig().getDouble("race.start-trigger-height-tolerance", 2.0);
        return Double.isFinite(tolerance) && tolerance >= 0 ? tolerance : 2.0;
    }

    public static int minY(Location position, double tolerance) {
        return (int) Math.floor(position.getY() - tolerance) - 1;
    }

    public static int maxY(Location position, double tolerance) {
        return (int) Math.ceil(position.getY() + tolerance);
    }

    public static boolean isWithinHeight(Location position, int triggerY, double tolerance) {
        double distanceToSurface = Math.min(Math.abs(position.getY() - triggerY),
                Math.abs(position.getY() - (triggerY + 1.0)));
        return distanceToSurface <= tolerance;
    }
}
