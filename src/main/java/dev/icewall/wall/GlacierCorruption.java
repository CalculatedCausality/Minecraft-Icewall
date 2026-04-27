package dev.icewall.wall;

import dev.icewall.config.IceWallConfig;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Progressively corrupts the environment in the corridor ahead of the glacier wall.
 *
 * Two passes run every tick:
 *
 * 1. Terrain band â€” random positions scattered across the full
 *    [minExploredX..maxExploredX] Ã— [wallFrontZ..wallFrontZ+CORRUPTION_RANGE] band.
 *    The probability of applying any effect scales linearly with closeness to the wall,
 *    so corruption is dense right at the leading edge and sparse at the far end.
 *
 * 2. Player proximity â€” for each player within CORRUPTION_RANGE of the wall, a small
 *    number of positions are sampled around the player, causing ice and frost to visibly
 *    creep into their immediate surroundings in real time.
 *
 * Effects by zone (distanceAhead = z - wallFrontZ):
 *   0â€“32 blocks  : aggressive â€” waterâ†’ice, vegetation frost-killed, snow layers placed
 *   32â€“128 blocks: moderate/light â€” waterâ†’ice, snow layers placed (no vegetation kill)
 */
public final class GlacierCorruption {
    private final Random rng = new Random();
    private final HeatmapTracker heatmapTracker;
    private final Set<BlockPos> encasedTreasures = new HashSet<>();
    private final Set<BlockPos> campfirePositions = new HashSet<>();
    private final CorruptionSpecialEffects specialEffects = new CorruptionSpecialEffects();

    public GlacierCorruption(HeatmapTracker tracker) {
        this.heatmapTracker = tracker;
    }

    public void tick(ServerLevel world, IceWallState state) {
        if (!state.isActive()) {
            return;
        }

        int wallZ = state.getWallFrontZ();
        int minX  = state.getMinExploredX();
        int maxX  = state.getMaxExploredX();
        if (minX > maxX) {
            return;
        }

        // Periodic support systems
        heatmapTracker.maybeDecay(world.getGameTime());
        if (world.getGameTime() % IceWallConfig.CAMPFIRE_SCAN_INTERVAL_TICKS == 0L) {
            scanForCampfires(world, wallZ, minX, maxX);
        }
        if (rng.nextInt(40) == 0) tryFissure(world, wallZ, minX, maxX);
        if (rng.nextInt(20) == 0) tryIceSpike(world, wallZ, minX, maxX);
        if (rng.nextInt(200) == 0) scanForTreasure(world, wallZ, minX, maxX);

        // Special effects â€” ice blooms, glass shatter, portal sealing, chest rerolls
        specialEffects.tick(world, state);

        // --- Pass 1: terrain band ---
        int width = maxX - minX + 1;
        for (int i = 0; i < IceWallConfig.CORRUPTION_TERRAIN_SAMPLES; i++) {
            int x = minX + rng.nextInt(width);
            int z = wallZ + rng.nextInt(IceWallConfig.CORRUPTION_RANGE);
            int distAhead = z - wallZ;
            double chance = 1.0 - (double) distAhead / IceWallConfig.CORRUPTION_RANGE;
            if (rng.nextDouble() > chance) {
                continue;
            }
            // Campfire safe zones slow corruption by 70% within their radius
            if (isCampfireProtected(x, z) && rng.nextDouble() < 0.7) {
                continue;
            }
            heatmapTracker.record(x, z);
            tryCorrupt(world, wallZ, x, z, distAhead);
        }

        // --- Pass 2: player proximity ---
        for (ServerPlayer player : world.players()) {
            if (player.isSpectator()) {
                continue;
            }
            int playerZ = player.blockPosition().getZ();
            int distAhead = playerZ - wallZ;
            if (distAhead <= 0 || distAhead > IceWallConfig.CORRUPTION_RANGE) {
                continue;
            }
            double intensity = 1.0 - (double) distAhead / IceWallConfig.CORRUPTION_RANGE;
            int radius = IceWallConfig.CORRUPTION_PLAYER_RADIUS;
            for (int i = 0; i < IceWallConfig.CORRUPTION_PLAYER_SAMPLES; i++) {
                if (rng.nextDouble() > intensity) {
                    continue;
                }
                int px = player.blockPosition().getX() + rng.nextInt(radius * 2 + 1) - radius;
                int pz = playerZ + rng.nextInt(radius * 2 + 1) - radius;
                tryCorrupt(world, wallZ, px, pz, pz - wallZ);
            }
        }

        glaciatedSurfacePass(world, state);
    }

    private void tryCorrupt(ServerLevel world, int wallZ, int x, int z, int distAhead) {
        // Skip unloaded chunks â€” we never force-load for corruption
        if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) {
            return;
        }

        // WORLD_SURFACE heightmap returns the Y of the first air-like block above the surface,
        // so the actual top non-air block is one below that.
        int surfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        if (surfaceY < world.getMinY()) {
            return;
        }

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, surfaceY, z);
        BlockState surface = world.getBlockState(pos);

        if (!surface.getFluidState().isEmpty()) {
            // Surface is standing water or lava â€” freeze it solid
            world.setBlock(pos, Blocks.ICE.defaultBlockState(), Block.UPDATE_CLIENTS);

        } else if (isFarmland(surface) && distAhead < IceWallConfig.CORRUPTION_KILL_VEGETATION_DISTANCE) {
            // Farmland freezes solid: rich soil â†’ coarse dirt
            world.setBlock(pos, Blocks.COARSE_DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);

        } else if (isCrop(surface) && distAhead < IceWallConfig.CORRUPTION_KILL_VEGETATION_DISTANCE) {
            // Crop flash-frozen: replace with dead bush for visual
            world.setBlock(pos, Blocks.DEAD_BUSH.defaultBlockState(),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);

        } else if (isVegetation(surface) && distAhead < IceWallConfig.CORRUPTION_KILL_VEGETATION_DISTANCE) {
            // Aggressive zone: frost kills surface plants.
            // Suppress drops so dead vegetation doesn't litter the ground.
            world.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            // Try to place a snow layer on the newly exposed block below
            pos.setY(surfaceY - 1);
            BlockState below = world.getBlockState(pos);
            if (canHoldSnow(below)) {
                pos.setY(surfaceY);
                world.setBlock(pos, Blocks.SNOW.defaultBlockState(), Block.UPDATE_CLIENTS);
            }

        } else if (canHoldSnow(surface)) {
            // Place a snow layer on top of the solid surface block
            pos.setY(surfaceY + 1);
            if (world.getBlockState(pos).isAir()) {
                world.setBlock(pos, Blocks.SNOW.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    /**
     * Returns true for blocks that are non-solid, non-air, and non-fluid â€”
     * i.e., surface plants, short grass, flowers, torches, etc. that frost should kill.
     */
    private static boolean isVegetation(BlockState state) {
        return !state.isSolid()
            && !state.isAir()
            && state.getFluidState().isEmpty();
    }

    /** Returns true for farmland blocks. */
    private static boolean isFarmland(BlockState state) {
        return state.getBlock() == Blocks.FARMLAND;
    }

    /**
     * Returns true for planted crops (wheat, carrots, potatoes, beetroot, melon/pumpkin
     * stems, nether wart, sweet berry bush).
     */
    private static boolean isCrop(BlockState state) {
        Block b = state.getBlock();
        return b == Blocks.WHEAT
                || b == Blocks.CARROTS
                || b == Blocks.POTATOES
                || b == Blocks.BEETROOTS
                || b == Blocks.MELON_STEM
                || b == Blocks.PUMPKIN_STEM
                || b == Blocks.NETHER_WART
                || b == Blocks.SWEET_BERRY_BUSH
                || b == Blocks.TORCHFLOWER_CROP
                || b == Blocks.PITCHER_CROP;
    }

    /**
     * Returns true for solid blocks that can hold a snow layer on top.
     * Ice variants and existing snow are excluded to avoid double-layering.
     */
    private static boolean canHoldSnow(BlockState state) {
        if (!state.isSolid()) {
            return false;
        }
        Block b = state.getBlock();
        return b != Blocks.ICE
            && b != Blocks.PACKED_ICE
            && b != Blocks.BLUE_ICE
            && b != Blocks.FROSTED_ICE
            && b != Blocks.SNOW_BLOCK
            && b != Blocks.SNOW
            && b != Blocks.BARRIER;
    }

    // -----------------------------------------------------------------------
    // Surface features
    // -----------------------------------------------------------------------

    /** Crack the ground open with a 1-wide vertical fissure, 3â€“8 blocks deep. */
    private void tryFissure(ServerLevel world, int wallZ, int minX, int maxX) {
        if (minX >= maxX) return;
        int x = minX + rng.nextInt(maxX - minX + 1);
        int z = wallZ + IceWallConfig.FISSURE_DISTANCE_MIN
                + rng.nextInt(IceWallConfig.FISSURE_DISTANCE_MAX - IceWallConfig.FISSURE_DISTANCE_MIN + 1);
        if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) return;
        int surfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        if (surfaceY < world.getMinY() + 4) return;
        int depth = 3 + rng.nextInt(6);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, surfaceY, z);
        for (int dy = 0; dy < depth; dy++) {
            pos.setY(surfaceY - dy);
            BlockState s = world.getBlockState(pos);
            if (!s.isSolid()) break;
            world.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /** Shoot a packed-ice pillar 4â€“12 blocks above the surface, 8â€“40 blocks ahead. */
    private void tryIceSpike(ServerLevel world, int wallZ, int minX, int maxX) {
        if (minX >= maxX) return;
        int x = minX + rng.nextInt(maxX - minX + 1);
        int distAhead = IceWallConfig.ICE_SPIKE_MIN_DIST
                + rng.nextInt(IceWallConfig.ICE_SPIKE_MAX_DIST - IceWallConfig.ICE_SPIKE_MIN_DIST + 1);
        int z = wallZ + distAhead;
        if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) return;
        int surfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        int height = 4 + rng.nextInt(9);
        for (int dy = 0; dy < height; dy++) {
            BlockPos pos = new BlockPos(x, surfaceY + dy, z);
            if (!world.getBlockState(pos).isAir()) continue;
            world.setBlock(pos, Blocks.PACKED_ICE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        if (height > 6 && rng.nextBoolean()) {
            for (int dx = -1; dx <= 1; dx++) {
                BlockPos base = new BlockPos(x + dx, surfaceY, z);
                BlockState bs = world.getBlockState(base);
                if (bs.isAir() || bs.canBeReplaced()) {
                    world.setBlock(base, Blocks.PACKED_ICE.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /** Scan underground for chest/barrel blocks and encase them in blue ice. */
    private void scanForTreasure(ServerLevel world, int wallZ, int minX, int maxX) {
        if (minX >= maxX) return;
        int width = maxX - minX + 1;
        for (int i = 0; i < 5; i++) {
            int x = minX + rng.nextInt(width);
            int z = wallZ + rng.nextInt(IceWallConfig.TREASURE_ENCASE_DISTANCE);
            if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) continue;
            int surfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            int minY = world.getMinY();
            if (surfaceY - minY < 4) continue;
            int y = minY + rng.nextInt(surfaceY - minY - 4);
            BlockPos pos = new BlockPos(x, y, z);
            if (!isChestBlock(world.getBlockState(pos))) continue;
            if (encasedTreasures.contains(pos)) continue;
            encaseInIce(world, pos);
            encasedTreasures.add(pos);
        }
    }

    private void encaseInIce(ServerLevel world, BlockPos center) {
        BlockState iceState = Blocks.BLUE_ICE.defaultBlockState();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos p = center.offset(dx, dy, dz);
                    if (world.getChunk(p.getX() >> 4, p.getZ() >> 4, ChunkStatus.FULL, false) == null) continue;
                    BlockState existing = world.getBlockState(p);
                    if (!existing.isAir() && !existing.canBeReplaced()) continue;
                    world.setBlock(p, iceState, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    private static boolean isChestBlock(BlockState state) {
        Block b = state.getBlock();
        return b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.BARREL;
    }

    // -----------------------------------------------------------------------
    // Glaciated surface pass â€” converts the landscape behind the wall to frozen tundra.
    // This runs on a slow 2-second cycle over a small random sample, permanently
    // transforming terrain the wall has already consumed.
    // -----------------------------------------------------------------------

    private void glaciatedSurfacePass(ServerLevel world, IceWallState state) {
        if (world.getGameTime() % 40L != 0L) return;
        int wallZ = state.getWallFrontZ();
        int minX  = state.getMinExploredX();
        int maxX  = state.getMaxExploredX();
        if (minX >= maxX) return;
        int width = maxX - minX + 1;
        for (int i = 0; i < 10; i++) {
            int x = minX + rng.nextInt(width);
            int z = wallZ - 1 - rng.nextInt(64);
            if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) continue;
            int surfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            if (surfaceY < world.getMinY()) continue;
            BlockPos pos = new BlockPos(x, surfaceY, z);
            BlockState s = world.getBlockState(pos);
            Block b = s.getBlock();
            if (b == Blocks.GRASS_BLOCK || b == Blocks.DIRT || b == Blocks.DIRT_PATH) {
                world.setBlock(pos, Blocks.SNOW_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
            } else if (b == Blocks.SAND || b == Blocks.GRAVEL) {
                world.setBlock(pos, Blocks.PACKED_ICE.defaultBlockState(), Block.UPDATE_CLIENTS);
            } else if (!s.getFluidState().isEmpty()) {
                world.setBlock(pos, Blocks.ICE.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Campfire safe zones
    // -----------------------------------------------------------------------

    private void scanForCampfires(ServerLevel world, int wallZ, int minX, int maxX) {
        if (minX >= maxX) return;
        int width = maxX - minX + 1;
        campfirePositions.removeIf(pos -> {
            if (world.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false) == null) return false;
            Block b = world.getBlockState(pos).getBlock();
            return b != Blocks.CAMPFIRE && b != Blocks.SOUL_CAMPFIRE;
        });
        for (int i = 0; i < 20; i++) {
            int x = minX + rng.nextInt(width);
            int z = wallZ + rng.nextInt(Math.min(100, IceWallConfig.CORRUPTION_RANGE));
            if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) continue;
            int sy = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            for (int dy = 0; dy < 4; dy++) {
                BlockPos pos = new BlockPos(x, sy - dy, z);
                Block b = world.getBlockState(pos).getBlock();
                if (b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE) {
                    campfirePositions.add(pos);
                }
            }
        }
    }

    private boolean isCampfireProtected(int x, int z) {
        int r2 = IceWallConfig.CAMPFIRE_SLOW_RADIUS * IceWallConfig.CAMPFIRE_SLOW_RADIUS;
        for (BlockPos fire : campfirePositions) {
            int dx = fire.getX() - x;
            int dz = fire.getZ() - z;
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }
}
