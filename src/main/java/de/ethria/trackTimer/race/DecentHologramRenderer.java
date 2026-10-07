package de.ethria.trackTimer.race;

import eu.decentsoftware.holograms.api.DHAPI;
import eu.decentsoftware.holograms.api.DecentHologramsAPI;
import eu.decentsoftware.holograms.api.holograms.Hologram;
import eu.decentsoftware.holograms.display.DisplayBase;
import eu.decentsoftware.holograms.display.DisplaySettings;
import eu.decentsoftware.holograms.display.DisplayService;
import eu.decentsoftware.holograms.display.TextDisplay;
import eu.decentsoftware.holograms.display.attribute.DisplayAttribute;
import eu.decentsoftware.holograms.display.attribute.definition.BillboardAttributeDefinition;
import eu.decentsoftware.holograms.display.attribute.definition.ScaleAttributeDefinition;
import eu.decentsoftware.holograms.display.attribute.definition.TextLineWidthAttributeDefinition;
import eu.decentsoftware.holograms.display.attribute.value.display.BillboardConstraintsValue;
import eu.decentsoftware.holograms.display.attribute.value.primitives.IntegerValue;
import eu.decentsoftware.holograms.display.attribute.value.primitives.Vector3fValue;
import eu.decentsoftware.holograms.platform.api.data.DecentLocation;
import eu.decentsoftware.holograms.platform.api.data.display.DisplayBillboardConstraints;
import org.bukkit.Location;

import java.util.List;

/** Isolated so TrackTimer can load when DecentHolograms is absent or disabled. */
public final class DecentHologramRenderer {
    private DecentHologramRenderer() { }

    public static void remove(String hologramName) {
        Hologram oldHologram = DHAPI.getHologram(hologramName);
        if (oldHologram != null) DHAPI.removeHologram(hologramName);
        String displayName = hologramName + "_display";
        DisplayService displayService = DecentHologramsAPI.get().getDisplayModule().getDisplayService();
        if (displayService.getDisplay(displayName) != null) displayService.deleteDisplay(displayName);
    }

    public static void render(String hologramName, Location location, List<String> lines,
                              float scaleX, float scaleY, int maximumLineWidth) {
        String displayName = hologramName + "_display";
        remove(hologramName);

        DisplayService displayService = DecentHologramsAPI.get().getDisplayModule().getDisplayService();

        TextDisplay display = new TextDisplay(displayName, decentLocation(location), new DisplaySettings());
        display.setLines(lines);
        display.setAttribute(ScaleAttributeDefinition.KEY,
                new DisplayAttribute<>(ScaleAttributeDefinition.KEY,
                        new Vector3fValue(scaleX, scaleY, 1f)));
        display.setAttribute(TextLineWidthAttributeDefinition.KEY,
                new DisplayAttribute<>(TextLineWidthAttributeDefinition.KEY,
                        new IntegerValue(Math.max(1, maximumLineWidth))));
        display.setAttribute(BillboardAttributeDefinition.KEY,
                new DisplayAttribute<>(BillboardAttributeDefinition.KEY,
                        new BillboardConstraintsValue(DisplayBillboardConstraints.FIXED)));
        displayService.registerDisplay(display);
        displayService.updateDisplay(display);
    }

    private static DecentLocation decentLocation(Location location) {
        return new DecentLocation(location.getWorld().getName(), location.getX(), location.getY(),
                location.getZ(), location.getYaw(), location.getPitch());
    }

    public static void updateLines(String hologramName, List<String> lines) {
        DisplayService service = DecentHologramsAPI.get().getDisplayModule().getDisplayService();
        DisplayBase display = service.getDisplay(hologramName + "_display");
        if (!(display instanceof TextDisplay text)) throw new IllegalStateException("Live display no longer exists.");
        text.setLines(lines);
        service.updateDisplay(text);
    }
}
