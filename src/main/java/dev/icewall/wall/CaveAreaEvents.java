package dev.icewall.wall;

import dev.icewall.config.IceWallConfig;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.server.level.ServerLevel;

/**
 * Periodic area-wide underground events triggered from {@link CaveCorruption}.
 *
 *  8.  Cryo-flood              — powder-snow fills a random underground air pocket
 *  9.  Stalactite barrage      — burst of downward dripstone on cave ceilings
 * 10.  Magma freeze            — caps exposed magma blocks with obsidian/ice
 * 11.  Sculk crystallisation   — stone cave walls grow sculk veins
 * 12.  Glow lichen freeze      — glow lichen replaced with packed ice
 * 13.  Amethyst resonance      — amethyst clusters shatter; Levitation jolt on players
 */
final class CaveAreaEvents {

    private final Random rng;

    CaveAreaEvents(Random rng) {
        this.rng = rng;
    }

    /** Called every tick from {@link CaveCorruption#tick} on the periodic cadence. */
    void tick(ServerLevel world, int wallZ, int minX, int maxX) {
        long gt = world.getGameTime();

        if (gt % 80L == 0L && rng.nextInt(4) == 0) {
            triggerCryoFlood(world, wallZ, minX, maxX);
        }
        if (gt % 60L == 0L && rng.nextInt(3) == 0) {
            triggerStalactiteBarrage(world, wallZ, minX, maxX);
        }
        if (gt % 100L == 0L) {
            triggerMagmaFreeze(world, wallZ, minX, maxX);
        }
        if (gt % 50L == 0L && rng.nextInt(2) == 0) {
            triggerSculkCrystallisation(world, wallZ, minX, maxX);
        }
        if (gt % 70L == 0L) {
            triggerGlowLichenFreeze(world, wallZ, minX, maxX);
        }
        if (gt % 120L == 0L && rng.nextInt(2) == 0) {
            triggerAmethystResonance(world, wallZ, minX, maxX);
        }
    }

    // -----------------------------------------------------------------------
    // 8. Cryo-flood
    // -----------------------------------------------------------------------

    private void triggerCryoFlood(ServerLevel world, int wallZ, int minX, int maxX) {
        int x = minX + rng.nextInt(Math.max(1, maxX - minX));
        int z = wallZ - rng.nextInt(IceWallConfig.CAVE_SOUND_DISTANCE);
        if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) return;
        int surfY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        int targetY = world.getMinY() + rng.nextInt(Math.max(1, (surfY / 2) - world.getMinY()));
        int r = IceWallConfig.CRYO_FLOOD_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > r * r) continue;
                    BlockPos p = new BlockPos(x + dx, targetY + dy, z + dz);
                    if (world.getChunk(p.getX() >> 4, p.getZ() >> 4, ChunkStatus.FULL, false) == null) continue;
                    if (world.getBlockState(p).isAir()) {
                        world.setBlock(p, Blocks.POWDER_SNOW.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        world.playSound(null, new BlockPos(x, targetY, z),
                SoundEvents.BUCKET_EMPTY_POWDER_SNOW, SoundSource.BLOCKS, 1.5f, 0.6f);
    }

    // -----------------------------------------------------------------------
    // 9. Stalactite barrage
    // -----------------------------------------------------------------------

    private void triggerStalactiteBarrage(ServerLevel world, int wallZ, int minX, int maxX) {
        int width = Math.max(1, maxX - minX);
        for (int i = 0; i < IceWallConfig.STALA_BARRAGE_COUNT; i++) {
            int x = minX + rng.nextInt(width);
            int z = wallZ - rng.nextInt(IceWallConfig.STALACTITE_DISTANCE);
            if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) continue;
            int surfY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            int y = world.getMinY() + rng.nextInt(Math.max(1, surfY - world.getMinY() - 4));
            BlockPos air = new BlockPos(x, y, z);
            if (!world.getBlockState(air).isAir()) continue;
            BlockPos above = air.above();
            if (!isCollapsibleStone(world.getBlockState(above))) continue;
            world.setBlock(air, Blocks.POINTED_DRIPSTONE.defaultBlockState()
                    .setValue(BlockStateProperties.VERTICAL_DIRECTION, Direction.DOWN),
                    Block.UPDATE_CLIENTS);
        }
        world.playSound(null,
                new BlockPos((minX + maxX) / 2,
                        world.getHeight(Heightmap.Types.WORLD_SURFACE, (minX + maxX) / 2, wallZ), wallZ),
                SoundEvents.POINTED_DRIPSTONE_BREAK, SoundSource.BLOCKS, 1.5f, 0.5f);
    }

    // -----------------------------------------------------------------------
    // 10. Magma freeze
    // -----------------------------------------------------------------------

    private void triggerMagmaFreeze(ServerLevel world, int wallZ, int minX, int maxX) {
        int width = Math.max(1, maxX - minX);
        for (int i = 0; i < 16; i++) {
            int x = minX + rng.nextInt(width);
            int z = wallZ - rng.nextInt(IceWallConfig.CAVE_SOUND_DISTANCE);
            if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) continue;
            int surfY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            for (int y = world.getMinY(); y < surfY; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (world.getBlockState(pos).getBlock() == Blocks.MAGMA_BLOCK) {
                    BlockPos above = pos.above();
                    if (world.getBlockState(above).isAir()) {
                        world.setBlock(above, Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                    for (Direction dir : Direction.values()) {
                        BlockPos adj = pos.relative(dir);
                        if (world.getBlockState(adj).isAir()) {
                            world.setBlock(adj, Blocks.PACKED_ICE.defaultBlockState(), Block.UPDATE_CLIENTS);
                        }
                    }
                    world.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1.0f, 0.8f);
                    break;
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // 11. Sculk crystallisation
    // -----------------------------------------------------------------------

    private void triggerSculkCrystallisation(ServerLevel world, int wallZ, int minX, int maxX) {
        int width = Math.max(1, maxX - minX);
        for (int i = 0; i < 24; i++) {
            int x = minX + rng.nextInt(width);
            int z = wallZ - rng.nextInt(IceWallConfig.SCULK_SPREAD_DISTANCE);
            if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) continue;
            int surfY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            int y = world.getMinY() + rng.nextInt(Math.max(1, surfY - world.getMinY()));
            BlockPos pos = new BlockPos(x, y, z);
            BlockState bs = world.getBlockState(pos);
            if (isStoneVariant(bs) && isAdjacentToAir(world, pos)) {
                world.setBlock(pos, Blocks.SCULK.defaultBlockState(), Block.UPDATE_CLIENTS);
            } else if (bs.isAir()) {
                for (Direction dir : Direction.values()) {
                    BlockPos adj = pos.relative(dir);
                    if (world.getBlockState(adj).isSolid()) {
                        world.setBlock(pos, Blocks.SCULK_VEIN.defaultBlockState(), Block.UPDATE_CLIENTS);
                        break;
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // 12. Glow lichen freeze
    // -----------------------------------------------------------------------

    private void triggerGlowLichenFreeze(ServerLevel world, int wallZ, int minX, int maxX) {
        int width = Math.max(1, maxX - minX);
        for (int i = 0; i < 16; i++) {
            int x = minX + rng.nextInt(width);
            int z = wallZ - rng.nextInt(IceWallConfig.LICHEN_FREEZE_DISTANCE);
            if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) continue;
            int surfY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            for (int y = world.getMinY(); y < surfY; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (world.getBlockState(pos).getBlock() == Blocks.GLOW_LICHEN) {
                    world.setBlock(pos, Blocks.PACKED_ICE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    break;
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // 13. Amethyst resonance
    // -----------------------------------------------------------------------

    private void triggerAmethystResonance(ServerLevel world, int wallZ, int minX, int maxX) {
        int width = Math.max(1, maxX - minX);
        int shattered = 0;
        for (int i = 0; i < 12 && shattered < 4; i++) {
            int x = minX + rng.nextInt(width);
            int z = wallZ - rng.nextInt(IceWallConfig.CAVE_SOUND_DISTANCE);
            if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) continue;
            int surfY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            for (int y = world.getMinY(); y < surfY; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                Block b = world.getBlockState(pos).getBlock();
                if (b == Blocks.AMETHYST_CLUSTER
                        || b == Blocks.LARGE_AMETHYST_BUD
                        || b == Blocks.MEDIUM_AMETHYST_BUD) {
                    if (rng.nextInt(IceWallConfig.AMETHYST_SHATTER_CHANCE) != 0) continue;
                    world.removeBlock(pos, false);
                    world.playSound(null, pos, SoundEvents.AMETHYST_CLUSTER_BREAK,
                            SoundSource.BLOCKS, 1.5f, 0.8f + rng.nextFloat() * 0.4f);
                    AABB box = new AABB(x - 16, y - 8, z - 16, x + 16, y + 8, z + 16);
                    for (LivingEntity le : world.getEntitiesOfClass(LivingEntity.class, box, e -> true)) {
                        le.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 8, 0, false, false));
                    }
                    shattered++;
                    break;
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Static helpers (shared with CaveCorruption via package-private access)
    // -----------------------------------------------------------------------

    static boolean isCollapsibleStone(BlockState state) {
        Block b = state.getBlock();
        return b == Blocks.STONE
            || b == Blocks.DEEPSLATE
            || b == Blocks.TUFF
            || b == Blocks.DIORITE
            || b == Blocks.ANDESITE
            || b == Blocks.GRANITE
            || b == Blocks.COBBLESTONE;
    }

    static boolean isStoneVariant(BlockState state) {
        return isCollapsibleStone(state) || state.getBlock() == Blocks.CALCITE;
    }

    static boolean isAdjacentToAir(ServerLevel world, BlockPos pos) {
        return world.getBlockState(pos.north()).isAir()
            || world.getBlockState(pos.south()).isAir()
            || world.getBlockState(pos.east()).isAir()
            || world.getBlockState(pos.west()).isAir()
            || world.getBlockState(pos.above()).isAir()
            || world.getBlockState(pos.below()).isAir();
    }
}
