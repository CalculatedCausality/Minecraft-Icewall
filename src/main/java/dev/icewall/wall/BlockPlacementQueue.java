package dev.icewall.wall;

import dev.icewall.config.IceWallConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Keeps one extendable task per nearby X column instead of creating a new task
 * for every column on every wall advance. The visible face is filled first;
 * the rest of each column is backfilled with whatever tick budget remains.
 */
public final class BlockPlacementQueue {
    private static final int PLAYER_COLUMN_RADIUS = 48;
    private static final int WORK_PER_COLUMN = 8;
    private static final int SURFACE_DEPTH = 8;
    private static final int SURFACE_HEIGHT = 24;

    private final Map<Integer, ColumnTask> columns = new HashMap<>();
    private final Random rng = new Random();

    public void process(ServerLevel world, IceWallState state) {
        int wallZ = state.getWallFrontZ();
        Set<Integer> wanted = new HashSet<>();
        for (ServerPlayer player : world.players()) {
            int centerX = player.blockPosition().getX();
            for (int x = centerX - PLAYER_COLUMN_RADIUS; x <= centerX + PLAYER_COLUMN_RADIUS; x++) {
                int targetZ = wallZ + columnLeadVariation(x);
                if (world.getChunkSource().getChunkNow(x >> 4, targetZ >> 4) == null) continue;
                wanted.add(x);
                ColumnTask task = columns.get(x);
                if (task == null || targetZ < task.surfaceZ - 1 || targetZ - task.surfaceZ > 16) {
                    columns.put(x, new ColumnTask(x, wallZ, targetZ));
                } else {
                    task.extendTo(targetZ);
                }
            }
        }
        // A column leaving the player's area can be recreated when they return.
        // Old terrain stays saved without retaining tasks for the whole world.
        columns.keySet().removeIf(x -> !wanted.contains(x));

        List<ColumnTask> active = new ArrayList<>(columns.values());
        Collections.shuffle(active, rng);
        int budget = IceWallConfig.MAX_BLOCKS_PER_TICK;
        for (ColumnTask task : active) {
            if (budget == 0) break;
            budget -= task.fill(world, Math.min(WORK_PER_COLUMN, budget), true);
        }
        for (ColumnTask task : active) {
            if (budget == 0) break;
            budget -= task.fill(world, Math.min(WORK_PER_COLUMN, budget), false);
        }
    }

    private static int columnLeadVariation(int x) {
        int h = x * 0x9E3779B9;
        h ^= h >>> 16;
        return (h & Integer.MAX_VALUE) % (IceWallConfig.LEAD_VARIATION + 1);
    }

    private static boolean shouldReplace(BlockState state) {
        if (state.is(Blocks.PACKED_ICE) || state.is(Blocks.BEDROCK)) return false;
        return IceWallConfig.REPLACE_SOLIDS || state.isAir() || !state.getFluidState().isEmpty()
                || state.canBeReplaced() || !state.blocksMotion();
    }

    private static final class ColumnTask {
        private final int x;
        private int targetZ;
        private int surfaceZ;
        private int surfaceY = Integer.MIN_VALUE;
        private int deepZ;
        private int deepY = Integer.MIN_VALUE;
        private final Map<Integer, Integer> originalHeights = new HashMap<>();

        private ColumnTask(int x, int startZ, int targetZ) {
            this.x = x;
            this.targetZ = targetZ;
            this.surfaceZ = startZ;
            this.deepZ = startZ;
        }

        private void extendTo(int z) {
            targetZ = Math.max(targetZ, z);
        }

        private int fill(ServerLevel world, int budget, boolean surface) {
            int examined = 0;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            if (!surface && deepZ < surfaceZ - 16) {
                deepZ = surfaceZ - 16;
                deepY = Integer.MIN_VALUE;
                originalHeights.keySet().removeIf(z -> z < deepZ);
            }
            while (examined < budget) {
                int z = surface ? surfaceZ : deepZ;
                if (z > targetZ || (!surface && z >= surfaceZ)) break;
                LevelChunk chunk = world.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) break;
                int height = surface
                        ? originalHeights.computeIfAbsent(z, ignored ->
                                chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15))
                        : originalHeights.get(z);
                int bottom = Math.max(world.getMinY(), height - SURFACE_DEPTH);
                int top = Math.min(world.getMaxY(), height + SURFACE_HEIGHT);
                int y;
                if (surface) {
                    if (surfaceY == Integer.MIN_VALUE) surfaceY = top - 1;
                    if (surfaceY < bottom) {
                        surfaceZ++;
                        surfaceY = Integer.MIN_VALUE;
                        continue;
                    }
                    y = surfaceY--;
                } else {
                    if (deepY == Integer.MIN_VALUE) deepY = world.getMinY();
                    if (deepY >= bottom && deepY < top) deepY = top;
                    if (deepY >= world.getMaxY()) {
                        originalHeights.remove(z);
                        deepZ++;
                        deepY = Integer.MIN_VALUE;
                        continue;
                    }
                    y = deepY++;
                }
                pos.set(x, y, z);
                if (shouldReplace(world.getBlockState(pos))) {
                    world.setBlock(pos, IceWallConfig.WALL_BLOCK, Block.UPDATE_CLIENTS);
                }
                examined++;
            }
            return examined;
        }
    }
}
