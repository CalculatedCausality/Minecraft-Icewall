package dev.icewall.wall;

import dev.icewall.config.IceWallConfig;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerLevel;
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
 * Dramatic special effects triggered by the advancing glacier:
 * ice-pillar blooms, glass shattering, Nether portal sealing,
 * and frozen-chest loot rerolls.
 *
 * Extracted from {@link GlacierCorruption} to keep that class focused on
 * terrain corruption. Call {@link #tick(ServerLevel, int, int, int)} once
 * per server tick, guarded by the same interval/chance logic as before.
 */
public final class CorruptionSpecialEffects {

    private final Random rng = new Random();

    /** Set of chest positions already rerolled so we never revisit them. */
    private final Set<BlockPos> rerolledChests = new HashSet<>();

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Runs all special-effect sub-systems for one server tick.
     * The caller is responsible for checking {@code state.isActive()} and
     * computing the correct interval/chance guards before calling this.
     */
    public void tick(ServerLevel world, IceWallState state) {
        if (!state.isActive()) return;
        int wallZ = state.getWallFrontZ();
        int minX  = state.getMinExploredX();
        int maxX  = state.getMaxExploredX();
        if (minX >= maxX) return;

        if (rng.nextInt(IceWallConfig.ICE_BLOOM_CHANCE) == 0) {
            triggerIcePillarBloom(world, wallZ, minX, maxX);
        }
        if (world.getGameTime() % 30L == 0L && rng.nextInt(2) == 0) {
            tickGlassShatter(world, wallZ, minX, maxX);
        }
        if (world.getGameTime() % IceWallConfig.PORTAL_SCAN_INTERVAL_TICKS == 0L) {
            sealNetherPortals(world, wallZ, minX, maxX);
        }
        if (world.getGameTime() % IceWallConfig.CHEST_REROLL_INTERVAL_TICKS == 0L) {
            tickFrozenChestReroll(world, wallZ, minX, maxX);
        }
    }

    // -----------------------------------------------------------------------
    // Ice pillar bloom — dramatic cluster of tall blue-ice columns
    // -----------------------------------------------------------------------

    private void triggerIcePillarBloom(ServerLevel world, int wallZ, int minX, int maxX) {
        int clusterX = minX + rng.nextInt(maxX - minX);
        int clusterZ = wallZ + IceWallConfig.ICE_BLOOM_DIST_MIN
                + rng.nextInt(IceWallConfig.ICE_BLOOM_DIST_RANGE);

        for (int p = 0; p < IceWallConfig.ICE_BLOOM_PILLARS; p++) {
            int ox = clusterX + rng.nextInt(IceWallConfig.ICE_BLOOM_SPREAD * 2 + 1)
                    - IceWallConfig.ICE_BLOOM_SPREAD;
            int oz = clusterZ + rng.nextInt(IceWallConfig.ICE_BLOOM_SPREAD * 2 + 1)
                    - IceWallConfig.ICE_BLOOM_SPREAD;
            if (world.getChunk(ox >> 4, oz >> 4, ChunkStatus.FULL, false) == null) continue;
            int surfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE, ox, oz);
            int height = IceWallConfig.ICE_BLOOM_HEIGHT_MIN
                    + rng.nextInt(IceWallConfig.ICE_BLOOM_HEIGHT_RANGE);
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
        world.playSound(null, new BlockPos(clusterX, 64, clusterZ),
                SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 2.0f,
                0.3f + rng.nextFloat() * 0.2f);
    }

    // -----------------------------------------------------------------------
    // Glass shatter — structural glass cracks under the glacier's cold pressure
    // -----------------------------------------------------------------------

    private void tickGlassShatter(ServerLevel world, int wallZ, int minX, int maxX) {
        int width = maxX - minX + 1;
        int samples = 6;
        for (int i = 0; i < samples; i++) {
            int x = minX + rng.nextInt(width);
            int z = wallZ + rng.nextInt(IceWallConfig.GLASS_SHATTER_DISTANCE);
            if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) continue;
            int surfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            for (int y = world.getMinY(); y <= surfaceY + 16; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState bs = world.getBlockState(pos);
                if (isGlass(bs)) {
                    world.removeBlock(pos, false);
                    world.playSound(null, pos, SoundEvents.GLASS_BREAK,
                            SoundSource.BLOCKS, 0.8f + rng.nextFloat() * 0.4f,
                            1.0f + rng.nextFloat() * 0.5f);
                    break;
                }
            }
        }
    }

    private static boolean isGlass(BlockState state) {
        Block b = state.getBlock();
        return b == Blocks.GLASS
                || b == Blocks.GLASS_PANE
                || b == Blocks.TINTED_GLASS
                || b.getDescriptionId().contains("stained_glass");
    }

    // -----------------------------------------------------------------------
    // Portal sealing — Nether portals frozen shut by the glacier
    // -----------------------------------------------------------------------

    private void sealNetherPortals(ServerLevel world, int wallZ, int minX, int maxX) {
        int width = maxX - minX + 1;
        int samples = 12;
        for (int i = 0; i < samples; i++) {
            int x = minX + rng.nextInt(width);
            int z = wallZ + rng.nextInt(IceWallConfig.PORTAL_SEAL_DISTANCE);
            if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) continue;
            int surfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            for (int y = world.getMinY(); y <= surfaceY + 16; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (world.getBlockState(pos).getBlock() != Blocks.NETHER_PORTAL) continue;
                sealPortalCluster(world, pos);
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
                break;
            }
        }
    }

    private void sealPortalCluster(ServerLevel world, BlockPos start) {
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

    private void tickFrozenChestReroll(ServerLevel world, int wallZ, int minX, int maxX) {
        int width = maxX - minX + 1;
        int samples = 8;
        for (int i = 0; i < samples; i++) {
            int x = minX + rng.nextInt(width);
            // Sample BEHIND the wall — already glaciated territory
            int z = wallZ - 1 - rng.nextInt(IceWallConfig.CHEST_REROLL_SCAN_DEPTH);
            if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) continue;
            int surfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            for (int y = world.getMinY(); y <= surfaceY + 4; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (rerolledChests.contains(pos)) break;
                BlockState bs = world.getBlockState(pos);
                if (bs.getBlock() instanceof ChestBlock) {
                    BlockEntity be = world.getBlockEntity(pos);
                    if (be instanceof BaseContainerBlockEntity container) {
                        container.clearContent();
                        int count = 4 + rng.nextInt(4);
                        java.util.List<Integer> slots = new java.util.ArrayList<>();
                        for (int s = 0; s < container.getContainerSize(); s++) slots.add(s);
                        java.util.Collections.shuffle(slots, rng);
                        for (int j = 0; j < count && j < slots.size(); j++) {
                            container.setItem(slots.get(j),
                                    GLACIER_LOOT[rng.nextInt(GLACIER_LOOT.length)].copy());
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
