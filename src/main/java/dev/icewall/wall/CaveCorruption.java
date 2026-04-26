package dev.icewall.wall;

import dev.icewall.config.IceWallConfig;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

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
