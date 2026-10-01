package de.ethria.trackTimer.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;

/**
 * A single {@code /tracktimer <name>} subcommand. Each subcommand is
 * implemented in its own class so command logic, permission and Brigadier
 * wiring stay together and new subcommands can be added without touching
 * existing ones.
 */
public interface SubCommand {

    /**
     * The subcommand's literal name, e.g. "help" or "reload".
     */
    String name();

    /**
     * The permission required to run this subcommand.
     */
    String permission();

    /**
     * Builds the Brigadier node for this subcommand, usable as a child of
     * the root {@code /tracktimer} command.
     */
    LiteralCommandNode<CommandSourceStack> build();
}
