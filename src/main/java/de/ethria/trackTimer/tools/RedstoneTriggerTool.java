package de.ethria.trackTimer.tools;

import de.ethria.trackTimer.gui.EditorGuiContext;
import de.ethria.trackTimer.gui.EventEditorGui;

/** Selects start locations whose redstone signal transition starts the event. */
public final class RedstoneTriggerTool extends StartTriggerTool {
    public RedstoneTriggerTool(EditorGuiContext context, EventEditorGui eventEditorGui) {
        super(context, eventEditorGui, "start", "REDSTONE_SIGNAL", "redstone");
    }
}
