package dev.icewall.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.icewall.wall.IceWallState;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.Level;

public final class IceWallCommands {
    private IceWallCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("icewall")
                // --- anyone ---
                .then(Commands.literal("status")
                    .executes(context -> status(context.getSource())))
                .then(Commands.literal("distance")
                    .executes(context -> distance(context.getSource())))
                // --- OP only ---
                .then(Commands.literal("start")
                    .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                    .executes(context -> setActive(context.getSource(), true)))
                .then(Commands.literal("stop")
                    .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                    .executes(context -> setActive(context.getSource(), false)))
                .then(Commands.literal("setpos")
                    .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                    .then(Commands.argument("z", IntegerArgumentType.integer())
                        .executes(context -> setPos(context.getSource(), IntegerArgumentType.getInteger(context, "z")))))
                .then(Commands.literal("speed")
                    .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                    .then(Commands.argument("seconds", FloatArgumentType.floatArg(0.05F, 3600F))
                        .executes(context -> setSpeed(context.getSource(), FloatArgumentType.getFloat(context, "seconds")))))
        ));
    }

    private static int status(CommandSourceStack source) {
        IceWallState state = getState(source);
        float secondsPerBlock = state.getAdvanceIntervalTicks() / 20.0F;
        source.sendSuccess(() -> Component.literal(
            "Ice wall " + (state.isActive() ? "\u25b6 active" : "\u23f8 paused")
                + " | front Z=" + state.getWallFrontZ()
                + " | start Z=" + state.getStartZ()
                + " | width=" + state.getExploredWidth() + " blocks"
                + " | speed=" + secondsPerBlock + "s/block"
        ), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int distance(CommandSourceStack source) {
        IceWallState state = getState(source);
        ServerLevel world = source.getServer().getLevel(Level.OVERWORLD);
        if (world == null) {
            source.sendFailure(Component.literal("Overworld is not loaded."));
            return 0;
        }

        if (source.getPlayer() == null) {
            source.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }

        int playerZ = source.getPlayer().blockPosition().getZ();
        int wallZ = state.getWallFrontZ();
        int dist = playerZ - wallZ;
        if (dist <= 0) {
            source.sendSuccess(() -> Component.literal("You are inside the glacier. This should not be possible for long."), false);
        } else {
            source.sendSuccess(() -> Component.literal("You are " + dist + " blocks ahead of the glacier."), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int setActive(CommandSourceStack source, boolean active) {
        IceWallState state = getState(source);
        state.setActive(active);
        source.sendSuccess(() -> Component.literal("Ice wall " + (active ? "started" : "stopped")), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int setPos(CommandSourceStack source, int z) {
        IceWallState state = getState(source);
        state.setWallFrontZ(z);
        source.sendSuccess(() -> Component.literal("Wall front Z set to " + z), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int setSpeed(CommandSourceStack source, float seconds) {
        IceWallState state = getState(source);
        int ticks = Math.max(1, Math.round(seconds * 20.0F));
        state.setAdvanceIntervalTicks(ticks);
        source.sendSuccess(() -> Component.literal("Wall speed set to " + seconds + "s/block (" + ticks + " ticks)"), true);
        return Command.SINGLE_SUCCESS;
    }

    private static IceWallState getState(CommandSourceStack source) {
        ServerLevel world = source.getServer().getLevel(Level.OVERWORLD);
        if (world == null) {
            throw new IllegalStateException("Overworld is not loaded");
        }

        IceWallState state = IceWallState.get(world);
        state.initializeIfNeeded(world);
        return state;
    }
}
