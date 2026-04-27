package dev.icewall.wall;

import dev.icewall.config.IceWallConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Manages the crew of villager workers that follow the rail construction head.
 *
 * Extracted from {@link GlacierRailNetwork} to keep that class focused on
 * layout, track-laying, and supply logistics.
 *
 * Call {@link #tick(ServerLevel, int, int, int)} once per the worker-management
 * interval, passing the current wall Z, rail head X, and rail head Z so the crew
 * can be positioned and replaced correctly.
 */
public final class RailWorkerCrew {

    private final List<UUID> workerIds = new ArrayList<>();
    private final Random rng = new Random();

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Ensures the right number of workers exist near the head and teleports strays back. */
    public void tick(ServerLevel world, int wallZ, int headX, int headZ) {
        // Remove entries for dead / removed entities
        workerIds.removeIf(id -> {
            var entity = world.getEntity(id);
            return entity == null || entity.isRemoved();
        });

        // Teleport stray workers back to near the head
        for (UUID id : workerIds) {
            var entity = world.getEntity(id);
            if (entity instanceof Villager v) {
                int distZ = Math.abs(v.blockPosition().getZ() - headZ);
                if (distZ > IceWallConfig.RAIL_WORKER_MAX_WANDER) {
                    int tz = headZ - 4 + rng.nextInt(8);
                    int ty = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, headX, tz);
                    v.teleportTo(headX + (rng.nextDouble() * 4 - 2), ty, tz);
                }
            }
        }

        // Spawn replacements for missing workers
        int needed = IceWallConfig.RAIL_WORKER_COUNT - workerIds.size();
        for (int i = 0; i < needed; i++) {
            spawnWorker(world, headX, headZ);
        }
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private void spawnWorker(ServerLevel world, int x, int headZ) {
        if (world.getChunk(x >> 4, headZ >> 4, ChunkStatus.FULL, false) == null) return;

        int spawnZ = headZ - 3 + rng.nextInt(7);
        int spawnY = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, spawnZ);

        Villager worker = EntityType.VILLAGER.create(world, EntitySpawnReason.NATURAL);
        if (worker == null) return;

        worker.teleportTo(x + (rng.nextDouble() * 4 - 2), spawnY, spawnZ);
        worker.setVillagerData(
                worker.getVillagerData().withProfession(
                        world.registryAccess(), VillagerProfession.TOOLSMITH));
        worker.setCustomName(Component.literal("Rail Crew").withStyle(ChatFormatting.GOLD));
        worker.setCustomNameVisible(true);
        worker.setInvulnerable(true);
        worker.setPersistenceRequired();

        world.addFreshEntity(worker);
        workerIds.add(worker.getUUID());

        world.playSound(null, new BlockPos(x, spawnY, spawnZ),
                SoundEvents.VILLAGER_WORK_TOOLSMITH, SoundSource.NEUTRAL,
                0.6f, 1.0f + rng.nextFloat() * 0.2f);
    }
}
