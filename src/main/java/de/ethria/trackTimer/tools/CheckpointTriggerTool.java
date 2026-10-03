package de.ethria.trackTimer.tools;

import de.ethria.trackTimer.gui.EditorGuiContext;
import de.ethria.trackTimer.gui.EventEditorGui;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.SQLException;
import java.util.logging.Level;

/** Assigns the next checkpoint order when a checkpoint-selection session begins. */
public final class CheckpointTriggerTool extends StartTriggerTool {
    private final EditorGuiContext context;

    public CheckpointTriggerTool(EditorGuiContext context, EventEditorGui eventEditorGui) {
        super(context, eventEditorGui, "checkpoint");
        this.context = context;
    }

    @Override
    public void begin(Player player, long eventId, int overviewPage) {
        try {
            int nextOrder = context.database().nextCheckpointOrder(eventId);
            beginWithOrder(player, eventId, overviewPage, nextOrder);
            player.sendMessage(context.language().chat("trigger.checkpoint-order-selected",
                    de.ethria.trackTimer.language.LanguageManager.placeholders("number", nextOrder)));
        } catch (SQLException exception) {
            context.plugin().getLogger().log(Level.SEVERE, "Could not determine the next checkpoint order.", exception);
            player.sendMessage(context.language().chat("event.list-failed"));
        }
    }

    @Override @EventHandler public void onBlockInteract(PlayerInteractEvent event) { super.onBlockInteract(event); }
    @Override @EventHandler(priority = EventPriority.HIGHEST)
    public void onSelectionChat(AsyncChatEvent event) { super.onSelectionChat(event); }
    @Override @EventHandler public void onQuit(PlayerQuitEvent event) { super.onQuit(event); }
}
