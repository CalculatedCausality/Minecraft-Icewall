package dev.icewall.wall;

import dev.icewall.config.IceWallConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public final class BlockPlacementQueue {
    // ArrayList gives O(1) random-index access and O(1) swap-and-remove, enabling
    // random task selection each tick for an organic fill pattern.
    private final List<FillTask> readyTasks = new ArrayList<>();
    private final Map<Long, ArrayDeque<FillTask>> pendingByChunk = new HashMap<>();
    private final Random rng = new Random();

    /**
     * Enqueue the wall's leading edge as individual per-column tasks.
     * Each X column receives a deterministic Z offset in [0, LEAD_VARIATION] so the
     * glacier face is an uneven, organic slab rather than a flat plane.
     * Combined with random task selection in process(), columns fill in at different
     * rates each tick, making the ice look like it is slowly spreading and freezing.
     */
    public void enqueueLeadingEdge(ServerLevel world, int wallZ, int minX, int maxX) {
        if (minX > maxX) return;
        int minY = world.getMinY();
        int maxY = world.getMaxY();
        for (int x = minX; x <= maxX; x++) {
            int lead = columnLeadVariation(x);
            // topDown=true: ice descends from the sky/ceiling so in caves it appears to drip
            // down through the ceiling before sealing the floor — a more claustrophobic effect.
            routeTask(world, new FillTask(x, x, wallZ + lead, wallZ + lead, minY, maxY, true));
        }
    }

    /**
     * Enqueue a rectangular region for solid backfill (newly explored X bounds).
     * Processed in whatever order tasks are drawn, but since this fills already-passed
     * terrain the exact order does not matter visually.
     */
    public void enqueueRange(ServerLevel world, int minX, int maxX, int minZ, int maxZ) {
        if (minX > maxX || minZ > maxZ) {
            return;
        }
        routeTask(world, new FillTask(minX, maxX, minZ, maxZ, world.getMinY(), world.getMaxY(), false));
    }

    public void onChunkLoad(ServerLevel world, ChunkPos chunkPos) {
        ArrayDeque<FillTask> pendingTasks = pendingByChunk.remove(chunkPos.pack());
        if (pendingTasks == null) {
            return;
        }
        while (!pendingTasks.isEmpty()) {
            routeTask(world, pendingTasks.removeFirst());
        }
    }

    /**
     * Fill up to MAX_BLOCKS_PER_TICK blocks this tick, picking tasks in random order.
     * Random selection means different columns advance each tick, producing the organic
     * slow-spread appearance rather than a left-to-right sweep.
     */
    public void process(ServerLevel world) {
        int budget = IceWallConfig.MAX_BLOCKS_PER_TICK;
        // Snapshot the current list size so we never revisit tasks added this tick
        // by onChunkLoad callbacks, and so the loop terminates if budget reaches 0.
        int maxTasks = readyTasks.size();
        for (int i = 0; i < maxTasks && budget > 0 && !readyTasks.isEmpty(); i++) {
            int idx = rng.nextInt(readyTasks.size());
            FillTask task = readyTasks.get(idx);

            if (!task.isCurrentChunkLoaded(world)) {
                removeBySwap(idx);
                queuePending(task);
                continue;
            }

            budget -= task.fill(world, budget, IceWallConfig.WALL_BLOCK, IceWallConfig.REPLACE_SOLIDS);

            if (task.isComplete()) {
                removeBySwap(idx);
            }
            // If not complete the task stays in the list; a future tick's random pick
            // will eventually resume it.
        }
    }

    private void routeTask(ServerLevel world, FillTask task) {
        if (task.isCurrentChunkLoaded(world)) {
            readyTasks.add(task);
            return;
        }
        queuePending(task);
    }

    private void queuePending(FillTask task) {
        pendingByChunk.computeIfAbsent(task.currentChunkKey(), ignored -> new ArrayDeque<>()).addLast(task);
    }

    /** O(1) removal from ArrayList by swapping with the last element. */
    private void removeBySwap(int idx) {
        int last = readyTasks.size() - 1;
        if (idx != last) {
            readyTasks.set(idx, readyTasks.get(last));
        }
        readyTasks.remove(last);
    }

    /**
     * Maps an X coordinate to a deterministic lead offset in [0, LEAD_VARIATION].
     * Uses a fast integer hash so adjacent columns have uncorrelated offsets,
     * giving the wall face a natural, non-periodic jagged appearance.
     */
    private static int columnLeadVariation(int x) {
        int h = x * 0x9E3779B9;
        h ^= (h >>> 16);
        return Math.abs(h) % (IceWallConfig.LEAD_VARIATION + 1);
    }

    private static boolean shouldReplace(ServerLevel world, BlockPos.MutableBlockPos mutablePos, BlockState currentState, boolean replaceSolids) {
        if (currentState.getBlock() == Blocks.PACKED_ICE) {
            return false;
        }
        if (currentState.getBlock() == Blocks.BEDROCK) {
            return false;
        }
        if (replaceSolids) {
            return true;
        }
        if (currentState.isAir()) {
            return true;
        }
        if (!currentState.getFluidState().isEmpty()) {
            return true;
        }
        if (currentState.canBeReplaced()) {
            return true;
        }
        return !currentState.blocksMotion();
    }

    private static final class FillTask {
        private final int minX;
        private final int maxX;
        private final int minZ;
        private final int maxZ;
        private final int bottomY;
        private final int topYExclusive;
        private int currentX;
        private int currentY;
        private int currentZ;

        private final boolean topDown;

        private FillTask(int minX, int maxX, int minZ, int maxZ, int bottomY, int topYExclusive, boolean topDown) {
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.bottomY = bottomY;
            this.topYExclusive = topYExclusive;
            this.topDown = topDown;
            this.currentX = minX;
            this.currentY = topDown ? topYExclusive - 1 : bottomY;
            this.currentZ = minZ;
        }

        private int fill(ServerLevel world, int budget, BlockState wallState, boolean replaceSolids) {
            int blocksPlaced = 0;
            BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

            while (budget > 0 && !isComplete()) {
                if (!isCurrentChunkLoaded(world)) {
                    break;
                }

                mutablePos.set(currentX, currentY, currentZ);
                BlockState currentState = world.getBlockState(mutablePos);
                if (shouldReplace(world, mutablePos, currentState, replaceSolids)) {
                    world.setBlock(mutablePos, wallState, Block.UPDATE_CLIENTS);
                    budget -= 1;
                    blocksPlaced += 1;
                }

                advanceCursor();
            }

            return blocksPlaced;
        }

        private void advanceCursor() {
            if (topDown) {
                currentY -= 1;
                if (currentY >= bottomY) {
                    return;
                }
                currentY = topYExclusive - 1;
            } else {
                currentY += 1;
                if (currentY < topYExclusive) {
                    return;
                }
                currentY = bottomY;
            }
            currentX += 1;
            if (currentX <= maxX) {
                return;
            }
            currentX = minX;
            currentZ += 1;
        }

        private boolean isComplete() {
            return currentZ > maxZ;
        }

        private boolean isCurrentChunkLoaded(ServerLevel world) {
            int chunkX = currentX >> 4;
            int chunkZ = currentZ >> 4;
            return world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null;
        }

        private long currentChunkKey() {
            return ChunkPos.pack(currentX >> 4, currentZ >> 4);
        }
    }
}