package dev.icewall.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.icewall.wall.HeatmapTracker;
import dev.icewall.wall.IceWallAdvancer;
import dev.icewall.wall.IceWallState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

public final class IceWallCommands {
    private IceWallCommands() {
    }

    public static void register(IceWallAdvancer advancer) {
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
                .then(Commands.literal("spectate")
                    .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                    .executes(context -> spectateStart(context.getSource(), advancer))
                    .then(Commands.literal("stop")
                        .executes(context -> spectateStop(context.getSource(), advancer))))
                .then(Commands.literal("heatmap")
                    .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                    .executes(context -> heatmap(context.getSource(), advancer)))
                .then(Commands.literal("snapshot")
                    .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                    .executes(context -> snapshot(context.getSource())))
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

    // -----------------------------------------------------------------------
    // Spectator drift cam
    // -----------------------------------------------------------------------

    private static int spectateStart(CommandSourceStack source, IceWallAdvancer advancer) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }
        advancer.startSpectate(player);
        source.sendSuccess(() -> Component.literal("Spectator drift cam started. Use /icewall spectate stop to exit."), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int spectateStop(CommandSourceStack source, IceWallAdvancer advancer) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }
        boolean removed = advancer.stopSpectate(player);
        if (removed) {
            source.sendSuccess(() -> Component.literal("Spectator drift cam stopped."), false);
        } else {
            source.sendFailure(Component.literal("You were not in spectator drift mode."));
        }
        return Command.SINGLE_SUCCESS;
    }

    // -----------------------------------------------------------------------
    // Heatmap overlay — top-10 most-sampled chunks
    // -----------------------------------------------------------------------

    private static int heatmap(CommandSourceStack source, IceWallAdvancer advancer) {
        Map<Long, Integer> counts = advancer.getHeatmapTracker().getCounts();
        if (counts.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No heatmap data yet — the wall may not be active."), false);
            return Command.SINGLE_SUCCESS;
        }
        List<Map.Entry<Long, Integer>> top = counts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Long, Integer>, Integer>comparing(Map.Entry::getValue).reversed())
                .limit(10)
                .toList();
        source.sendSuccess(() -> Component.literal("☃ Glacier heatmap — top " + top.size() + " chunks:"), false);
        for (Map.Entry<Long, Integer> entry : top) {
            int chunkX = ChunkPos.getX(entry.getKey());
            int chunkZ = ChunkPos.getZ(entry.getKey());
            int samples = entry.getValue();
            String bar = "\u2588".repeat(Math.min(samples / 5 + 1, 20));
            source.sendSuccess(() -> Component.literal(
                "  chunk (" + chunkX + ", " + chunkZ + ") " + bar + " " + samples), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    // -----------------------------------------------------------------------
    // Replay snapshot — serialise wall state to an NBT file
    // -----------------------------------------------------------------------

    private static int snapshot(CommandSourceStack source) {
        IceWallState state = getState(source);
        try {
            Path dir = source.getServer().getWorldPath(LevelResource.ROOT).resolve("icewall_snapshots");
            Files.createDirectories(dir);
            String filename = "snapshot_" + System.currentTimeMillis() + ".dat";
            Path file = dir.resolve(filename);
            CompoundTag tag = new CompoundTag();
            tag.putInt("wallFrontZ", state.getWallFrontZ());
            tag.putInt("startZ", state.getStartZ());
            tag.putInt("minExploredX", state.getMinExploredX());
            tag.putInt("maxExploredX", state.getMaxExploredX());
            tag.putLong("tickAccumulator", state.getTickAccumulator());
            tag.putBoolean("active", state.isActive());
            tag.putInt("advanceIntervalTicks", state.getAdvanceIntervalTicks());
            NbtIo.writeCompressed(tag, file);
            source.sendSuccess(() -> Component.literal("Snapshot saved: " + filename), true);
        } catch (IOException e) {
            source.sendFailure(Component.literal("Failed to save snapshot: " + e.getMessage()));
        }
        return Command.SINGLE_SUCCESS;
    }
}
