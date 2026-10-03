package de.ethria.trackTimer.tools;

import de.ethria.trackTimer.gui.EditorGuiContext;
import de.ethria.trackTimer.gui.EventEditorGui;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Selects start locations whose redstone signal transition starts the event. */
public final class RedstoneTriggerTool extends StartTriggerTool {
    public RedstoneTriggerTool(EditorGuiContext context, EventEditorGui eventEditorGui) {
        super(context, eventEditorGui, "start", "REDSTONE_SIGNAL", "redstone");
    }

    @Override @EventHandler public void onBlockInteract(PlayerInteractEvent event) { super.onBlockInteract(event); }
    @Override @EventHandler(priority = EventPriority.HIGHEST)
    public void onSelectionChat(AsyncChatEvent event) { super.onSelectionChat(event); }
    @Override @EventHandler public void onQuit(PlayerQuitEvent event) { super.onQuit(event); }
}
