package de.ethria.trackTimer.tools;

import de.ethria.trackTimer.gui.EditorGuiContext;
import de.ethria.trackTimer.gui.EventEditorGui;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Configures and manages end triggers using the shared block trigger tool. */
public final class EndTriggerTool extends StartTriggerTool {
    public EndTriggerTool(EditorGuiContext context, EventEditorGui eventEditorGui) {
        super(context, eventEditorGui, "end");
    }

    @Override @EventHandler public void onBlockInteract(PlayerInteractEvent event) { super.onBlockInteract(event); }
    @Override @EventHandler(priority = EventPriority.HIGHEST)
    public void onSelectionChat(AsyncChatEvent event) { super.onSelectionChat(event); }
    @Override @EventHandler public void onQuit(PlayerQuitEvent event) { super.onQuit(event); }
}
