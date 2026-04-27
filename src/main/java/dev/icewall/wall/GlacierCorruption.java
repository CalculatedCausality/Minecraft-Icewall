package dev.icewall.wall;

import dev.icewall.config.IceWallConfig;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Progressively corrupts the environment in the corridor ahead of the glacier wall.
 *
 * Two passes run every tick:
 *
 * 1. Terrain band — random positions scattered across the full
 *    [minExploredX..maxExploredX] × [wallFrontZ..wallFrontZ+CORRUPTION_RANGE] band.
 *    The probability of applying any effect scales linearly with closeness to the wall,
 *    so corruption is dense right at the leading edge and sparse at the far end.
 *
 * 2. Player proximity — for each player within CORRUPTION_RANGE of the wall, a small
 *    number of positions are sampled around the player, causing ice and frost to visibly
 *    creep into their immediate surroundings in real time.
 *
 * Effects by zone (distanceAhead = z - wallFrontZ):
 *   0–32 blocks  : aggressive — water→ice, vegetation frost-killed, snow layers placed
 *   32–128 blocks: moderate/light — water→ice, snow layers placed (no vegetation kill)
 */
public final class GlacierCorruption {
    private final Random rng = new Random();
    private final HeatmapTracker heatmapTracker;
    private final Set<BlockPos> encasedTreasures = new HashSet<>();
    private final Set<BlockPos> campfirePositions = new HashSet<>();

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

        // Ice pillar bloom — a dramatic cluster of tall blue-ice columns erupts forward
        if (rng.nextInt(IceWallConfig.ICE_BLOOM_CHANCE) == 0) {
            triggerIcePillarBloom(world, wallZ, minX, maxX);
        }

        // Glass shatter — windows and panes near the wall crack and fall
        if (world.getGameTime() % 30L == 0L && rng.nextInt(2) == 0) {
            tickGlassShatter(world, wallZ, minX, maxX);
        }

        // Portal sealing — active Nether portals are frozen shut
        if (world.getGameTime() % IceWallConfig.PORTAL_SCAN_INTERVAL_TICKS == 0L) {
            sealNetherPortals(world, wallZ, minX, maxX);
        }

        // Frozen chest reroll — chests inside the glacier zone are restocked with survival loot
        if (world.getGameTime() % IceWallConfig.CHEST_REROLL_INTERVAL_TICKS == 0L) {
            tickFrozenChestReroll(world, wallZ, minX, maxX);
        }

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
        // Skip unloaded chunks — we never force-load for corruption
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
            // Surface is standing water or lava — freeze it solid
            world.setBlock(pos, Blocks.ICE.defaultBlockState(), Block.UPDATE_CLIENTS);

        } else if (isFarmland(surface) && distAhead < IceWallConfig.CORRUPTION_KILL_VEGETATION_DISTANCE) {
            // Farmland freezes solid: rich soil → coarse dirt
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
     * Returns true for blocks that are non-solid, non-air, and non-fluid —
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

    /** Crack the ground open with a 1-wide vertical fissure, 3–8 blocks deep. */
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

    /** Shoot a packed-ice pillar 4–12 blocks above the surface, 8–40 blocks ahead. */
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
    // Glaciated surface pass — converts the landscape behind the wall to frozen tundra.
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

    // -----------------------------------------------------------------------
    // Ice pillar bloom — dramatic cluster of tall blue-ice columns erupts forward
    // -----------------------------------------------------------------------

    /**
     * Spawns a burst of ICE_BLOOM_PILLARS tall blue-ice / packed-ice columns in a
     * cluster ahead of the wall.  The columns vary in height so the formation looks
     * organic rather than uniform.
     */
    private void triggerIcePillarBloom(ServerLevel world, int wallZ, int minX, int maxX) {
        if (minX >= maxX) return;
        int clusterX = minX + rng.nextInt(maxX - minX);
        int clusterZ = wallZ + IceWallConfig.ICE_BLOOM_DIST_MIN
                + rng.nextInt(IceWallConfig.ICE_BLOOM_DIST_RANGE);

        for (int p = 0; p < IceWallConfig.ICE_BLOOM_PILLARS; p++) {
            int ox = clusterX + rng.nextInt(IceWallConfig.ICE_BLOOM_SPREAD * 2 + 1) - IceWallConfig.ICE_BLOOM_SPREAD;
            int oz = clusterZ + rng.nextInt(IceWallConfig.ICE_BLOOM_SPREAD * 2 + 1) - IceWallConfig.ICE_BLOOM_SPREAD;
            if (world.getChunk(ox >> 4, oz >> 4, ChunkStatus.FULL, false) == null) continue;
            int surfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE, ox, oz);
            int height = IceWallConfig.ICE_BLOOM_HEIGHT_MIN
                    + rng.nextInt(IceWallConfig.ICE_BLOOM_HEIGHT_RANGE);
            // Alternate blue ice and packed ice for visual interest
            BlockState pillarBlock = (p % 3 == 0)
                    ? Blocks.BLUE_ICE.defaultBlockState()
                    : Blocks.PACKED_ICE.defaultBlockState();
            for (int dy = 0; dy < height; dy++) {
                BlockPos pos = new BlockPos(ox, surfaceY + dy, oz);
                BlockState existing = world.getBlockState(pos);
                if (!existing.isAir() && !existing.canBeReplaced()) break;
                world.setBlock(pos, pillarBlock, Block.UPDATE_CLIENTS);
            }
        }
        // Play an ice-crack groan at the cluster centre
        world.playSound(null, new BlockPos(clusterX, 64, clusterZ),
                SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 2.0f,
                0.3f + rng.nextFloat() * 0.2f);
    }

    // -----------------------------------------------------------------------
    // Glass shatter — structural glass cracks under the glacier's cold pressure
    // -----------------------------------------------------------------------

    /**
     * Randomly removes glass blocks / panes within the close corruption zone,
     * simulating the thermal contraction and shattering of glass as the glacier
     * approaches.
     */
    private void tickGlassShatter(ServerLevel world, int wallZ, int minX, int maxX) {
        if (minX >= maxX) return;
        int width = maxX - minX + 1;
        int samples = 6;
        for (int i = 0; i < samples; i++) {
            int x = minX + rng.nextInt(width);
            int z = wallZ + rng.nextInt(IceWallConfig.GLASS_SHATTER_DISTANCE);
            if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) continue;
            int surfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            // Scan a vertical column for glass
            for (int y = world.getMinY(); y <= surfaceY + 16; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState bs = world.getBlockState(pos);
                if (isGlass(bs)) {
                    world.removeBlock(pos, false);
                    world.playSound(null, pos, SoundEvents.GLASS_BREAK,
                            SoundSource.BLOCKS, 0.8f + rng.nextFloat() * 0.4f,
                            1.0f + rng.nextFloat() * 0.5f);
                    break; // one shatter per column per tick pass
                }
            }
        }
    }

    private static boolean isGlass(BlockState state) {
        Block b = state.getBlock();
        return b == Blocks.GLASS
                || b == Blocks.GLASS_PANE
                || b == Blocks.TINTED_GLASS
                // check stained glass via block name (no static tag constant in 26.1.2)
                || b.getDescriptionId().contains("stained_glass");
    }

    // -----------------------------------------------------------------------
    // Portal sealing — Nether portals frozen shut by the glacier
    // -----------------------------------------------------------------------

    /**
     * Scans for NETHER_PORTAL blocks within PORTAL_SEAL_DISTANCE ahead of the wall
     * and removes them (leaving the obsidian frame).  Each sealed portal sends a
     * “Escape route sealed” actionbar message to all players within 80 blocks.
     */
    private void sealNetherPortals(ServerLevel world, int wallZ, int minX, int maxX) {
        if (minX >= maxX) return;
        int width = maxX - minX + 1;
        int samples = 12;
        for (int i = 0; i < samples; i++) {
            int x = minX + rng.nextInt(width);
            int z = wallZ + rng.nextInt(IceWallConfig.PORTAL_SEAL_DISTANCE);
            if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) continue;
            int surfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            // Scan vertical column for a portal block
            for (int y = world.getMinY(); y <= surfaceY + 16; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (world.getBlockState(pos).getBlock() != Blocks.NETHER_PORTAL) continue;
                // Found a portal — flood-fill to remove all contiguous portal blocks
                sealPortalCluster(world, pos);
                // Notify nearby players
                BlockPos epicentre = pos;
                world.players().forEach(player -> {
                    if (player.blockPosition().distSqr(epicentre) < 80.0 * 80.0) {
                        player.connection.send(new ClientboundSetActionBarTextPacket(
                                Component.literal("❎ Escape route sealed by the glacier.❎")
                                        .withStyle(net.minecraft.ChatFormatting.DARK_RED,
                                                net.minecraft.ChatFormatting.BOLD)));
                    }
                });
                world.playSound(null, pos, SoundEvents.BEACON_DEACTIVATE,
                        SoundSource.BLOCKS, 1.5f, 0.5f);
                break; // one portal sealed per column per scan pass
            }
        }
    }

    private void sealPortalCluster(ServerLevel world, BlockPos start) {
        // BFS removal of connected NETHER_PORTAL blocks (usually a 2x3 or 4x5 frame)
        java.util.Deque<BlockPos> queue = new java.util.ArrayDeque<>();
        Set<BlockPos> visited = new java.util.HashSet<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            if (world.getBlockState(cur).getBlock() != Blocks.NETHER_PORTAL) continue;
            world.setBlock(cur, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                BlockPos next = cur.relative(dir);
                if (!visited.contains(next)
                        && world.getBlockState(next).getBlock() == Blocks.NETHER_PORTAL) {
                    visited.add(next);
                    queue.add(next);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Frozen chest loot reroll
    // -----------------------------------------------------------------------

    /**
     * Survivor loot table: a curated set of survival items placed in chests that
     * the glacier has already passed over.  This rewards players who dare raid
     * the frozen zone, and adds narrative weight ("someone left this behind").
     */
    private static final ItemStack[] GLACIER_LOOT = {
            new ItemStack(Items.PACKED_ICE, 4),
            new ItemStack(Items.BREAD, 6),
            new ItemStack(Items.COOKED_BEEF, 4),
            new ItemStack(Items.LEATHER_HELMET),
            new ItemStack(Items.LEATHER_CHESTPLATE),
            new ItemStack(Items.IRON_SWORD),
            new ItemStack(Items.TORCH, 16),
            new ItemStack(Items.FLINT_AND_STEEL),
            new ItemStack(Items.SNOWBALL, 16),
            new ItemStack(Items.COAL, 8),
            new ItemStack(Items.ARROW, 12),
            new ItemStack(Items.BOW),
    };

    /** Set of chest positions we have already rerolled so we don't revisit them. */
    private final Set<BlockPos> rerolledChests = new HashSet<>();

    private void tickFrozenChestReroll(ServerLevel world, int wallZ, int minX, int maxX) {
        if (minX >= maxX) return;
        // Sample random positions BEHIND the wall (already glaciated)
        int width = maxX - minX + 1;
        int samples = 6;
        for (int i = 0; i < samples; i++) {
            int x = minX + rng.nextInt(width);
            // z is behind the wall face (negative offset = consumed territory)
            int z = wallZ - 1 - rng.nextInt(IceWallConfig.CHEST_REROLL_SCAN_DEPTH);
            if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) continue;
            int surfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            for (int y = surfaceY + 8; y >= world.getMinY(); y--) {
                BlockPos pos = new BlockPos(x, y, z);
                if (rerolledChests.contains(pos)) break; // already done this column
                BlockState bs = world.getBlockState(pos);
                if (bs.getBlock() instanceof ChestBlock) {
                    BlockEntity be = world.getBlockEntity(pos);
                    if (be instanceof BaseContainerBlockEntity container) {
                        container.clearContent();
                        // Place 4-7 random items from the loot table
                        int count = 4 + rng.nextInt(4);
                        java.util.List<Integer> slots = new java.util.ArrayList<>();
                        for (int s = 0; s < container.getContainerSize(); s++) slots.add(s);
                        java.util.Collections.shuffle(slots, rng);
                        for (int j = 0; j < count && j < slots.size(); j++) {
                            ItemStack loot = GLACIER_LOOT[rng.nextInt(GLACIER_LOOT.length)].copy();
                            container.setItem(slots.get(j), loot);
                        }
                        rerolledChests.add(pos);
                        world.playSound(null, pos, SoundEvents.CHEST_CLOSE,
                                SoundSource.BLOCKS, 0.8f, 0.6f + rng.nextFloat() * 0.2f);
                    }
                    break;
                }
            }
        }
    }
}
