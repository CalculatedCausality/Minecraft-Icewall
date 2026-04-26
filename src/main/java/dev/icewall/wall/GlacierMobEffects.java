package dev.icewall.wall;

import dev.icewall.config.IceWallConfig;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.monster.skeleton.Stray;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Handles mob-related glacier effects:
 *
 *  1. Mob freeze conversion — animals near the wall become hostile cold mobs
 *  2. Frozen mob statues    — rare: mob encased in packed ice (removed, cube left)
 *  3. Bat swarm flush       — bats spawn and flee south when wall enters a chunk
 *  4. Tree snap             — log tops break and drop in the aggressive zone
 *  5. Snowdrift accumulation behind the wall (handled in WeatherEffects; trees here only)
 */
public final class GlacierMobEffects {

    private final Random rng = new Random();

    public void tick(ServerLevel world, IceWallState state) {
        if (!state.isActive()) return;
        long gameTime = world.getGameTime();

        // Run mob conversion + tree snap on a 2-second cadence (avoid every-tick entity scan)
        if (gameTime % 40L == 0L) {
            tickMobConversion(world, state);
            tickTreeSnap(world, state);
        }

        // Bat flush on a slower cadence
        if (gameTime % 100L == 0L) {
            tickBatFlush(world, state);
        }
    }

    // -----------------------------------------------------------------------
    // 1 & 2. Mob conversion and frozen statues
    // -----------------------------------------------------------------------

    private void tickMobConversion(ServerLevel world, IceWallState state) {
        int wallZ = state.getWallFrontZ();
        int rangeZ = IceWallConfig.MOB_CONVERT_DISTANCE;
        int minX   = state.getMinExploredX();
        int maxX   = state.getMaxExploredX();

        // Collect living entities in the strip ahead of the wall
        net.minecraft.world.phys.AABB zone = new net.minecraft.world.phys.AABB(
                minX, world.getMinY(), wallZ,
                maxX, world.getMaxY(), wallZ + rangeZ);

        List<net.minecraft.world.entity.LivingEntity> candidates =
                world.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, zone,
                        e -> !e.isSpectator() && !(e instanceof net.minecraft.server.level.ServerPlayer));

        for (net.minecraft.world.entity.LivingEntity entity : candidates) {
            if (rng.nextInt(20) != 0) continue; // stagger: ~5% of candidates per 2s

            // Rare path: frozen statue (1 in 12 conversions)
            if (rng.nextInt(12) == 0) {
                freezeAsStatue(world, entity);
                continue;
            }

            convertMob(world, entity);
        }
    }

    private void convertMob(ServerLevel world, net.minecraft.world.entity.LivingEntity entity) {
        BlockPos pos = entity.blockPosition();
        net.minecraft.world.entity.EntityType<?> replacement = null;

        if (entity instanceof Cow
                || entity instanceof Sheep
                || entity instanceof Chicken) {
            replacement = EntityType.STRAY;
        } else if (entity instanceof Pig) {
            replacement = EntityType.ZOMBIFIED_PIGLIN;
        } else if (entity instanceof Zombie) {
            replacement = EntityType.STRAY;
        }

        if (replacement == null) return;

        entity.discard();
        net.minecraft.world.entity.Entity spawned = replacement.create(world, EntitySpawnReason.CONVERSION);
        if (spawned == null) return;
        spawned.teleportTo(pos.getX() + 0.5, (double) pos.getY(), pos.getZ() + 0.5);
        world.addFreshEntity(spawned);
    }

    private void freezeAsStatue(ServerLevel world, net.minecraft.world.entity.LivingEntity entity) {
        BlockPos pos = entity.blockPosition();
        if (world.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false) == null) return;
        entity.discard();
        world.setBlock(pos, Blocks.PACKED_ICE.defaultBlockState(), Block.UPDATE_CLIENTS);
        // Stack a second block above to give a "figure" silhouette
        BlockPos above = pos.above();
        if (world.getBlockState(above).isAir()) {
            world.setBlock(above, Blocks.PACKED_ICE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    // -----------------------------------------------------------------------
    // 3. Bat swarm flush
    // -----------------------------------------------------------------------

    private void tickBatFlush(ServerLevel world, IceWallState state) {
        int wallZ = state.getWallFrontZ();
        int minX  = state.getMinExploredX();
        int maxX  = state.getMaxExploredX();
        if (minX >= maxX) return;

        // Spawn bats at the wall's leading edge, fleeing south (positive Z)
        for (int i = 0; i < IceWallConfig.BAT_SWARM_COUNT; i++) {
            int x = minX + rng.nextInt(maxX - minX + 1);
            int z = wallZ + rng.nextInt(IceWallConfig.BAT_FLUSH_DISTANCE);
            if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) continue;
            int surfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            Bat bat = EntityType.BAT.create(world, EntitySpawnReason.NATURAL);
            if (bat == null) continue;
            bat.teleportTo(x + 0.5, (double) (surfaceY + 1 + rng.nextInt(8)), z + 0.5);
            // Give the bat a southward impulse
            bat.setDeltaMovement(
                    (rng.nextDouble() - 0.5) * 0.4,
                    rng.nextDouble() * 0.3 + 0.1,
                    rng.nextDouble() * 0.5 + 0.3);
            world.addFreshEntity(bat);
        }
    }

    // -----------------------------------------------------------------------
    // 4. Tree snap
    // -----------------------------------------------------------------------

    private void tickTreeSnap(ServerLevel world, IceWallState state) {
        int wallZ = state.getWallFrontZ();
        int minX  = state.getMinExploredX();
        int maxX  = state.getMaxExploredX();
        if (minX >= maxX) return;

        for (int i = 0; i < 4; i++) {
            int x = minX + rng.nextInt(maxX - minX + 1);
            int z = wallZ + rng.nextInt(IceWallConfig.TREE_SNAP_DISTANCE + 1);
            if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) continue;
            int surfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            trySnapTree(world, x, surfaceY, z);
        }
    }

    private void trySnapTree(ServerLevel world, int x, int surfaceY, int z) {
        // Find a log column starting at the surface
        int logBase = -1;
        int logHeight = 0;
        for (int dy = 0; dy < 16; dy++) {
            BlockState bs = world.getBlockState(new BlockPos(x, surfaceY + dy, z));
            if (isLog(bs)) {
                if (logBase < 0) logBase = surfaceY + dy;
                logHeight++;
            } else if (logBase >= 0) {
                break;
            }
        }
        if (logHeight < 4) return; // not tall enough to snap

        // Remove the top 2–3 log blocks and drop them as items
        int snapCount = 2 + rng.nextInt(2);
        for (int s = 0; s < snapCount; s++) {
            int y = logBase + logHeight - 1 - s;
            BlockPos pos = new BlockPos(x, y, z);
            BlockState bs = world.getBlockState(pos);
            if (!isLog(bs)) break;
            bs.getBlock().playerWillDestroy(world, pos, bs, null);
            world.removeBlock(pos, false);
            Block.dropResources(bs, world, pos);
        }
    }

    private static boolean isLog(BlockState state) {
        return state.is(net.minecraft.tags.BlockTags.LOGS);
    }
}
