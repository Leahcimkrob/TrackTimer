package de.ethria.trackTimer.tools;

import de.ethria.trackTimer.gui.EditorGuiContext;
import de.ethria.trackTimer.gui.EventEditorGui;

/** Configures and manages end triggers using the shared block trigger tool. */
public final class EndTriggerTool extends StartTriggerTool {
    public EndTriggerTool(EditorGuiContext context, EventEditorGui eventEditorGui) {
        super(context, eventEditorGui, "end");
    }
}
