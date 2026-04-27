package dev.icewall.wall;

import dev.icewall.config.IceWallConfig;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.wolf.Wolf;
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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

/**
 * Handles mob-related glacier effects:
 *
 *  1. Mob freeze conversion — animals near the wall become hostile cold mobs
 *  2. Frozen mob statues    — rare: mob encased in packed ice (removed, cube left)
 *  3. Bat swarm flush       — bats spawn and flee south when wall enters a chunk
 *  4. Tree snap             — log tops break and drop in the aggressive zone
 *  5. Animal panic          — passive animals flee northward en masse
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

        // Animal panic every 10 ticks — passive mobs flee the wall
        if (gameTime % 10L == 0L) {
            tickAnimalPanic(world, state);
        }

        // Stray hunting party — spawn a squad of Strays that hunt surviving players
        if (gameTime % IceWallConfig.STRAY_SPAWN_INTERVAL_TICKS == 0L) {
            tickStrayHuntingParty(world, state);
        }

        // Wolf flight — tamed wolves flee the wall every 20 ticks
        if (gameTime % 20L == 0L) {
            tickWolfFlight(world, state);
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

    // -----------------------------------------------------------------------
    // 5. Animal panic — passive mobs flee away from the advancing wall
    // -----------------------------------------------------------------------

    /**
     * Every 10 ticks, any passive animal within ANIMAL_PANIC_DISTANCE of the wall
     * receives a positive-Z (northward / away from wall) velocity nudge that scales
     * with closeness.  This creates the spectacle of stampeding wildlife ahead of
     * the glacier without any AI modification.
     */
    private void tickAnimalPanic(ServerLevel world, IceWallState state) {
        int wallZ = state.getWallFrontZ();
        int minX  = state.getMinExploredX();
        int maxX  = state.getMaxExploredX();
        if (minX >= maxX) return;

        AABB zone = new AABB(
                minX, world.getMinY(), wallZ,
                maxX, world.getMaxY(), wallZ + IceWallConfig.ANIMAL_PANIC_DISTANCE);

        List<Animal> animals = world.getEntitiesOfClass(Animal.class, zone,
                e -> !e.isSpectator());

        for (Animal animal : animals) {
            if (rng.nextInt(3) != 0) continue; // stagger to avoid all animals moving identically

            int dist = (int)(animal.getZ() - wallZ);
            double fraction = 1.0 - (double) dist / IceWallConfig.ANIMAL_PANIC_DISTANCE;
            fraction = Math.max(0.05, fraction);

            // Boost Z velocity (positive Z = away from wall) with a small random X spread
            net.minecraft.world.phys.Vec3 motion = animal.getDeltaMovement();
            double pushZ = 0.28 * fraction;
            double pushX = (rng.nextDouble() - 0.5) * 0.12 * fraction;
            double pushY = animal.onGround() ? 0.22 * fraction : 0.0;
            animal.setDeltaMovement(motion.x + pushX, motion.y + pushY, motion.z + pushZ);
            animal.hurtMarked = true; // flag for client movement sync

            // Very close animals get a panic sound
            if (dist < 20 && rng.nextInt(8) == 0) {
                world.playSound(null, animal.blockPosition(),
                        SoundEvents.GLASS_BREAK, SoundSource.AMBIENT,
                        0.3f, 1.8f + rng.nextFloat() * 0.4f);
            }
        }
    }

    // -----------------------------------------------------------------------
    // 6. Stray hunting party
    // -----------------------------------------------------------------------

    /**
     * Periodically spawns STRAY_PACK_SIZE Strays at the glacier’s leading edge.
     * They spawn slightly behind the wall face (so the wall has already
     * consumed their spawn chunk) and spread out to hunt any survivors ahead.
     * A brief actionbar warning is sent to all nearby players.
     */
    private void tickStrayHuntingParty(ServerLevel world, IceWallState state) {
        int wallZ = state.getWallFrontZ();
        int minX  = state.getMinExploredX();
        int maxX  = state.getMaxExploredX();
        if (minX >= maxX) return;

        // Check there is at least one live player within range to hunt
        boolean hasTarget = world.players().stream().anyMatch(p -> {
            if (p.isSpectator()) return false;
            int dist = p.blockPosition().getZ() - wallZ;
            return dist > 0 && dist <= IceWallConfig.STRAY_HUNT_DISTANCE;
        });
        if (!hasTarget) return;

        int spawned = 0;
        for (int attempt = 0; attempt < IceWallConfig.STRAY_PACK_SIZE * 3 && spawned < IceWallConfig.STRAY_PACK_SIZE; attempt++) {
            int x = minX + rng.nextInt(maxX - minX + 1);
            // Spawn at the wall face — just ahead so they walk into the player’s space
            int z = wallZ + 1 + rng.nextInt(8);
            if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) continue;
            int surfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            Stray stray = EntityType.STRAY.create(world, EntitySpawnReason.NATURAL);
            if (stray == null) continue;
            stray.teleportTo(x + 0.5, (double) surfaceY, z + 0.5);
            world.addFreshEntity(stray);
            spawned++;
        }

        if (spawned > 0) {
            // Warn nearby players
            BlockPos wallMid = new BlockPos((minX + maxX) / 2, 64, wallZ);
            for (ServerPlayer player : world.players()) {
                if (player.isSpectator()) continue;
                int dist = player.blockPosition().getZ() - wallZ;
                if (dist <= 0 || dist > IceWallConfig.STRAY_HUNT_DISTANCE) continue;
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(
                        net.minecraft.network.chat.Component.literal(
                                "❄ ⚠ Cold hunters emerge from the glacier — "
                                + spawned + " strays!")
                                .withStyle(net.minecraft.ChatFormatting.AQUA)));
            }
            world.playSound(null, wallMid, SoundEvents.STRAY_AMBIENT,
                    SoundSource.HOSTILE, 1.5f, 0.7f + rng.nextFloat() * 0.2f);
        }
    }

    // -----------------------------------------------------------------------
    // 7. Wolf / dog flight — tamed wolves panic and run from the wall
    // -----------------------------------------------------------------------

    /**
     * Wolves within WOLF_FLIGHT_DISTANCE of the wall receive a southward velocity
     * boost every 20 ticks, proportional to closeness.  Very close wolves also
     * produce a whimper to signal to their owner.
     */
    private void tickWolfFlight(ServerLevel world, IceWallState state) {
        int wallZ = state.getWallFrontZ();
        int minX  = state.getMinExploredX();
        int maxX  = state.getMaxExploredX();
        if (minX >= maxX) return;
        AABB zone = new AABB(minX, world.getMinY(), wallZ,
                maxX, world.getMaxY(), wallZ + IceWallConfig.WOLF_FLIGHT_DISTANCE);
        for (Wolf wolf : world.getEntitiesOfClass(Wolf.class, zone, e -> !e.isSpectator())) {
            int dist = (int)(wolf.getZ() - wallZ);
            double fraction = 1.0 - (double) dist / IceWallConfig.WOLF_FLIGHT_DISTANCE;
            fraction = Math.max(0.05, fraction);
            net.minecraft.world.phys.Vec3 motion = wolf.getDeltaMovement();
            wolf.setDeltaMovement(
                    motion.x + (rng.nextDouble() - 0.5) * 0.1 * fraction,
                    motion.y + (wolf.onGround() ? 0.3 * fraction : 0.0),
                    motion.z + 0.35 * fraction);
            wolf.hurtMarked = true;
            if (dist < 20 && rng.nextInt(4) == 0) {
                world.playSound(null, wolf.blockPosition(),
                        SoundEvents.WARDEN_SNIFF, SoundSource.NEUTRAL,
                        0.5f, 1.8f + rng.nextFloat() * 0.3f);
            }
        }
    }
}
