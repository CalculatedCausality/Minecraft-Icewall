package dev.icewall.wall;

import dev.icewall.config.IceWallConfig;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * Underground-specific glacier effects, running every server tick alongside GlacierCorruption.
 *
 * Features:
 *   1. Ceiling collapse  — stone/deepslate directly above a cave air pocket within
 *      CAVE_COLLAPSE_DISTANCE converts to gravel, which falls naturally next tick.
 *   2. Ice vein infiltration — stone cave walls 16–48 blocks ahead of the wall
 *      that are adjacent to air gradually become blue ice / packed ice, as if the
 *      cold is seeping through the rock.
 *   3. Torch / lantern / campfire snuffing — light sources in the aggressive zone
 *      (< CORRUPTION_KILL_VEGETATION_DISTANCE) are extinguished, plunging caves into
 *      darkness just before the wall arrives.
 *   4. Lava sealing — exposed lava source blocks capped with obsidian (lava is too
 *      hot to freeze, so it solidifies instead).
 *   5. Cave rumble — a low-pitched stone-break sound plays for underground players
 *      near the wall, volume scaling with closeness, staggered by entity id.
 */
public final class CaveCorruption {
    private final Random rng = new Random();
    private final HeatmapTracker heatmapTracker;

    public CaveCorruption(HeatmapTracker tracker) {
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

        int width = maxX - minX + 1;
        int minY  = world.getMinY();

        // --- Terrain band pass ---
        for (int i = 0; i < IceWallConfig.CAVE_TERRAIN_SAMPLES; i++) {
            int x = minX + rng.nextInt(width);
            int z = wallZ + rng.nextInt(IceWallConfig.CORRUPTION_RANGE);
            int distAhead = z - wallZ;

            // Closeness-weighted probability so the wall's immediate vicinity is dense
            double chance = 1.0 - (double) distAhead / IceWallConfig.CORRUPTION_RANGE;
            if (rng.nextDouble() > chance) {
                continue;
            }

            if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) {
                continue;
            }

            int surfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            int range = surfaceY - minY - 4;
            if (range < 1) {
                continue;
            }

            int y = minY + rng.nextInt(range);
            applyCaveEffect(world, x, y, z, distAhead);
        }

        // --- Player proximity pass (underground players only) ---
        for (ServerPlayer player : world.players()) {
            if (player.isSpectator()) {
                continue;
            }

            BlockPos pPos = player.blockPosition();
            int distAhead = pPos.getZ() - wallZ;
            if (distAhead <= 0 || distAhead > IceWallConfig.CORRUPTION_RANGE) {
                continue;
            }

            // Only apply cave effects when the player is genuinely underground
            int surfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE, pPos.getX(), pPos.getZ());
            if (pPos.getY() >= surfaceY - 4) {
                continue;
            }

            double intensity = 1.0 - (double) distAhead / IceWallConfig.CORRUPTION_RANGE;
            int radius = IceWallConfig.CAVE_PLAYER_RADIUS;

            for (int i = 0; i < IceWallConfig.CAVE_PLAYER_SAMPLES; i++) {
                if (rng.nextDouble() > intensity) {
                    continue;
                }
                int sx = pPos.getX() + rng.nextInt(radius * 2 + 1) - radius;
                int sy = pPos.getY() + rng.nextInt(radius * 2 + 1) - radius;
                int sz = pPos.getZ() + rng.nextInt(radius * 2 + 1) - radius;
                if (sy < world.getMinY() || sy >= world.getMaxY()) {
                    continue;
                }
                applyCaveEffect(world, sx, sy, sz, sz - wallZ);
            }

            // Cave rumble — low-pitched stone sound, staggered per player by entity id
            if (distAhead <= IceWallConfig.CAVE_SOUND_DISTANCE
                    && (world.getGameTime() + player.getId()) % IceWallConfig.CAVE_SOUND_INTERVAL == 0L) {
                float volume = (float) (1.0 - (double) distAhead / IceWallConfig.CAVE_SOUND_DISTANCE) * 0.8f + 0.2f;
                float pitch  = 0.25f + rng.nextFloat() * 0.15f; // very low pitch → mountain-groaning feel
                world.playSound(null, pPos, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, volume, pitch);
            }

            // 6. Darkness pulse — periodic Darkness effect for underground players
            if (distAhead <= IceWallConfig.CAVE_SOUND_DISTANCE
                    && (world.getGameTime() + player.getId()) % IceWallConfig.DARKNESS_INTERVAL == 0L) {
                player.addEffect(new MobEffectInstance(
                        MobEffects.DARKNESS, IceWallConfig.DARKNESS_DURATION, 0, false, false));
                if (distAhead < 24) {
                    player.connection.send(new ClientboundSetActionBarTextPacket(
                            Component.literal("§8§o▌ The cave goes pitch black...")));
                }
            }

            // 7. Cave gas pocket — rare Poison/Wither burst in the close zone
            if (distAhead < IceWallConfig.CAVE_GAS_DISTANCE
                    && rng.nextInt(200) == 0) {
                boolean isSevere = distAhead < 16;
                player.addEffect(new MobEffectInstance(
                        isSevere ? MobEffects.WITHER : MobEffects.POISON,
                        isSevere ? 60 : 100, 0, false, true));
                player.addEffect(new MobEffectInstance(MobEffects.HUNGER, 200, 0, false, false));
                player.connection.send(new ClientboundSetActionBarTextPacket(
                        Component.literal("§2§o⚗ A pocket of trapped glacial gas...").append(
                        Component.literal(isSevere ? " §4§lWITHER!" : " §aPoisoned!").withStyle(
                                isSevere ? net.minecraft.ChatFormatting.DARK_RED
                                         : net.minecraft.ChatFormatting.GREEN))));
                world.playSound(null, pPos, SoundEvents.LAVA_POP, SoundSource.BLOCKS, 1.0f, 0.4f);
            }
        }

        // Periodic area events (not per-player)
        tickAreaEvents(world, state, wallZ, minX, maxX);
    }

    /**
     * Periodic area-wide cave effects that run on a slower cadence.
     */
    private void tickAreaEvents(ServerLevel world, IceWallState state,
            int wallZ, int minX, int maxX) {
        long gt = world.getGameTime();

        // 8. Cryo-flood — every 80 ticks, try to flood a low cave pocket with powder snow
        if (gt % 80L == 0L && rng.nextInt(4) == 0) {
            triggerCryoFlood(world, wallZ, minX, maxX);
        }

        // 9. Stalactite barrage — every 60 ticks, rapid dripstone formation burst
        if (gt % 60L == 0L && rng.nextInt(3) == 0) {
            triggerStalactiteBarrage(world, wallZ, minX, maxX);
        }

        // 10. Magma freeze — every 100 ticks, cap exposed magma blocks
        if (gt % 100L == 0L) {
            triggerMagmaFreeze(world, wallZ, minX, maxX);
        }

        // 11. Sculk crystallisation — every 50 ticks, grow sculk veins on cave walls
        if (gt % 50L == 0L && rng.nextInt(2) == 0) {
            triggerSculkCrystallisation(world, wallZ, minX, maxX);
        }

        // 12. Glow lichen freeze — every 70 ticks, replace lichen with packed-ice skin
        if (gt % 70L == 0L) {
            triggerGlowLichenFreeze(world, wallZ, minX, maxX);
        }

        // 13. Amethyst resonance — every 120 ticks, shatter some amethyst clusters
        if (gt % 120L == 0L && rng.nextInt(2) == 0) {
            triggerAmethystResonance(world, wallZ, minX, maxX);
        }
    }

    private void applyCaveEffect(ServerLevel world, int x, int y, int z, int distAhead) {
        if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) {
            return;
        }
        if (y < world.getMinY() || y >= world.getMaxY()) {
            return;
        }
        if (distAhead < 0) {
            return;
        }

        // Permafrost: at deep Y levels, stone slowly becomes packed ice.
        if (y <= IceWallConfig.PERMAFROST_MAX_Y) {
            BlockState deep = world.getBlockState(new BlockPos(x, y, z));
            if (isStoneVariant(deep) && rng.nextInt(3) == 0) {
                world.setBlock(new BlockPos(x, y, z), Blocks.PACKED_ICE.defaultBlockState(), Block.UPDATE_CLIENTS);
                return;
            }
        }

        BlockPos pos   = new BlockPos(x, y, z);
        BlockState state = world.getBlockState(pos);

        // 1. Torch / lantern / campfire snuffing — aggressive zone only
        if (distAhead < IceWallConfig.CORRUPTION_KILL_VEGETATION_DISTANCE && isLightSource(state)) {
            world.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            return;
        }

        // 2. Lava sealing — replace lava source with obsidian at any distance
        if (state.getBlock() == Blocks.LAVA) {
            world.setBlock(pos, Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_CLIENTS);
            return;
        }

        if (state.isAir()) {
            // 3. Ceiling collapse — stone/deepslate above a cave void near the wall
            //    turns to gravel; UPDATE_ALL triggers gravity so it falls next tick.
            if (distAhead < IceWallConfig.CAVE_COLLAPSE_DISTANCE && y + 1 < world.getMaxY()
                    && rng.nextInt(5) == 0) {
                BlockPos above = pos.above();
                if (isCollapsibleStone(world.getBlockState(above))) {
                    world.setBlock(above, Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
            // Stalactite spears — form pointed dripstone hanging from cave ceilings.
            if (distAhead < IceWallConfig.STALACTITE_DISTANCE && y + 1 < world.getMaxY()
                    && rng.nextInt(4) == 0) {
                BlockPos above = pos.above();
                if (isCollapsibleStone(world.getBlockState(above))) {
                    BlockState stalactite = Blocks.POINTED_DRIPSTONE.defaultBlockState()
                            .setValue(BlockStateProperties.VERTICAL_DIRECTION, Direction.DOWN);
                    world.setBlock(pos, stalactite, Block.UPDATE_CLIENTS);
                }
            }
        } else if (distAhead >= 16 && distAhead <= 48 && isStoneVariant(state)) {
            // 4. Ice vein infiltration — cave-wall stone adjacent to air becomes blue/packed ice
            if (isAdjacentToAir(world, pos) && rng.nextInt(3) == 0) {
                BlockState iceVariant = (rng.nextInt(3) == 0)
                    ? Blocks.BLUE_ICE.defaultBlockState()
                    : Blocks.PACKED_ICE.defaultBlockState();
                world.setBlock(pos, iceVariant, Block.UPDATE_CLIENTS);
            }
        }
    }

    // -----------------------------------------------------------------------
    // New area-event methods
    // -----------------------------------------------------------------------

    /**
     * Cryo-flood: fill a small underground air pocket with powder snow,
     * simulating meltwater from above re-freezing in cave voids.
     */
    private void triggerCryoFlood(ServerLevel world, int wallZ, int minX, int maxX) {
        int x = minX + rng.nextInt(Math.max(1, maxX - minX));
        int z = wallZ - rng.nextInt(IceWallConfig.CAVE_SOUND_DISTANCE);
        if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) return;
        int surfY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        // Pick a Y level deep underground
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

    /**
     * Stalactite barrage: rapidly forms a burst of downward-pointing dripstone
     * on cave ceilings in the close zone, faster than the normal per-sample drip.
     */
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
        world.playSound(null, new BlockPos((minX + maxX) / 2,
                world.getHeight(Heightmap.Types.WORLD_SURFACE, (minX + maxX) / 2, wallZ), wallZ),
                SoundEvents.POINTED_DRIPSTONE_BREAK, SoundSource.BLOCKS, 1.5f, 0.5f);
    }

    /**
     * Magma freeze: caps exposed magma blocks near the wall with obsidian on top
     * and packed ice on the side, mimicking glacial groundwater extinguishing heat.
     */
    private void triggerMagmaFreeze(ServerLevel world, int wallZ, int minX, int maxX) {
        int width = Math.max(1, maxX - minX);
        int samples = 16;
        for (int i = 0; i < samples; i++) {
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
                    // Cap the magma itself with packed ice on any exposed face
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

    /**
     * Sculk crystallisation: stone cave walls adjacent to air in the corruption zone
     * grow a skin of sculk veins, as if dead life-energy is crystallising under cold.
     */
    private void triggerSculkCrystallisation(ServerLevel world, int wallZ, int minX, int maxX) {
        int width = Math.max(1, maxX - minX);
        int samples = 24;
        for (int i = 0; i < samples; i++) {
            int x = minX + rng.nextInt(width);
            int z = wallZ - rng.nextInt(IceWallConfig.SCULK_SPREAD_DISTANCE);
            if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) continue;
            int surfY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            int y = world.getMinY() + rng.nextInt(Math.max(1, surfY - world.getMinY()));
            BlockPos pos = new BlockPos(x, y, z);
            BlockState bs = world.getBlockState(pos);
            if (isStoneVariant(bs) && isAdjacentToAir(world, pos)) {
                // Replace the stone itself with sculk
                world.setBlock(pos, Blocks.SCULK.defaultBlockState(), Block.UPDATE_CLIENTS);
            } else if (bs.isAir()) {
                // Place sculk vein on any adjacent solid face
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

    /**
     * Glow lichen freeze: replaces glow lichen on cave walls with packed ice,
     * stripping bioluminescence and leaving a cold glassy surface.
     */
    private void triggerGlowLichenFreeze(ServerLevel world, int wallZ, int minX, int maxX) {
        int width = Math.max(1, maxX - minX);
        int samples = 16;
        for (int i = 0; i < samples; i++) {
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

    /**
     * Amethyst resonance: amethyst clusters near the wall shatter under vibrational stress
     * from the advancing glacier.  Cluster is removed (drops nothing — instant destruction),
     * underground players in range get a brief Levitation jolt and hear the chime.
     */
    private void triggerAmethystResonance(ServerLevel world, int wallZ, int minX, int maxX) {
        int width = Math.max(1, maxX - minX);
        int samples = 12;
        int shattered = 0;
        for (int i = 0; i < samples && shattered < 4; i++) {
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
                    // Levitation jolt to nearby underground players
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

    private static boolean isLightSource(BlockState state) {
        Block b = state.getBlock();
        return b == Blocks.TORCH
            || b == Blocks.WALL_TORCH
            || b == Blocks.SOUL_TORCH
            || b == Blocks.SOUL_WALL_TORCH
            || b == Blocks.LANTERN
            || b == Blocks.SOUL_LANTERN
            || b == Blocks.CAMPFIRE
            || b == Blocks.SOUL_CAMPFIRE;
    }

    private static boolean isCollapsibleStone(BlockState state) {
        Block b = state.getBlock();
        return b == Blocks.STONE
            || b == Blocks.DEEPSLATE
            || b == Blocks.TUFF
            || b == Blocks.DIORITE
            || b == Blocks.ANDESITE
            || b == Blocks.GRANITE
            || b == Blocks.COBBLESTONE;
    }

    private static boolean isStoneVariant(BlockState state) {
        return isCollapsibleStone(state) || state.getBlock() == Blocks.CALCITE;
    }

    private static boolean isAdjacentToAir(ServerLevel world, BlockPos pos) {
        return world.getBlockState(pos.north()).isAir()
            || world.getBlockState(pos.south()).isAir()
            || world.getBlockState(pos.east()).isAir()
            || world.getBlockState(pos.west()).isAir()
            || world.getBlockState(pos.above()).isAir()
            || world.getBlockState(pos.below()).isAir();
    }
}
