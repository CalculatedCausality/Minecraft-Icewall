package dev.icewall.city;

import dev.icewall.city.CityDomain.CityAnchor;
import dev.icewall.city.CityDomain.CityProfile;
import dev.icewall.config.IceWallConfig;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * Stateful runtime city events: evacuation rallying, gate defence incidents,
 * signal-fire ignition, and resident evacuation queuing.
 *
 * One instance should be created and kept alive for the life of the server; it
 * owns the per-city cooldown maps.
 */
public final class CityEventManager {

    private final Map<Long, Long> nextResidentEvacuationTick = new HashMap<>();
    private final Map<Long, Long> nextGateDefenseTick        = new HashMap<>();
    private final Set<Long>       litSignalFires              = new HashSet<>();

    private final CityLootHelper loot;

    public CityEventManager(CityLootHelper loot) {
        this.loot = loot;
    }

    // -----------------------------------------------------------------------
    // Evacuation rallying
    // -----------------------------------------------------------------------

    public void rallyResidents(ServerLevel world, CityAnchor anchor, CityProfile profile,
            long cityKey, long gameTime) {
        long next = nextResidentEvacuationTick.getOrDefault(cityKey, 0L);
        if (gameTime < next) return;
        nextResidentEvacuationTick.put(cityKey, gameTime + IceWallConfig.CITY_RESIDENT_EVACUATION_INTERVAL_TICKS);

        int centerX = anchor.chunkX() * 16 + 8;
        int centerZ = anchor.chunkZ() * 16 + 8;
        int radius  = IceWallConfig.CITY_RADIUS_CHUNKS * 16 + 12;
        BlockPos gate = evacuationGate(world, centerX, centerZ, anchor.seed());
        buildQueueMarker(world, gate, profile);

        AABB search = new AABB(centerX - radius, world.getMinY(), centerZ - radius,
                               centerX + radius, world.getMaxY(), centerZ + radius);
        int moved = 0;
        for (Villager v : world.getEntitiesOfClass(Villager.class, search, v -> !v.isRemoved() && v.isAlive())) {
            if (moved >= IceWallConfig.CITY_RESIDENT_EVACUATION_BATCH_SIZE) break;
            double spread = (moved % 5) - 2;
            BlockPos target = gate.offset((int) spread, 0, moved / 5);
            int y = CityBlockBuilder.surfaceY(world, target.getX(), target.getZ());
            v.getNavigation().moveTo(target.getX() + 0.5, y, target.getZ() + 0.5,
                    IceWallConfig.CITY_RESIDENT_EVACUATION_SPEED);
            if (!v.hasCustomName()) v.setCustomName(Component.literal(profile.name() + " Evacuee").withStyle(ChatFormatting.YELLOW));
            v.setCustomNameVisible(true);
            v.setPersistenceRequired();
            moved++;
        }
        if (moved > 0) world.playSound(null, gate, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 1.0f, 0.9f);
    }

    private void buildQueueMarker(ServerLevel world, BlockPos gate, CityProfile profile) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = gate.getX() + dx, z = gate.getZ() + dz;
                int y = CityBlockBuilder.surfaceY(world, x, z);
                world.setBlock(new BlockPos(x, y - 1, z), Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_CLIENTS);
                CityBlockBuilder.clearColumn(world, x, z, y, y + 2);
            }
        }
        world.setBlock(new BlockPos(gate.getX() - 2, gate.getY(),     gate.getZ() - 1), Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_CLIENTS);
        world.setBlock(new BlockPos(gate.getX() + 2, gate.getY(),     gate.getZ() - 1), Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_CLIENTS);
        world.setBlock(new BlockPos(gate.getX(),     gate.getY() + 1, gate.getZ() - 1), Blocks.BELL.defaultBlockState(),      Block.UPDATE_CLIENTS);
        BlockPos cache = new BlockPos(gate.getX(), gate.getY(), gate.getZ() + 1);
        if (world.getBlockState(cache).isAir()) {
            world.setBlock(cache, Blocks.BARREL.defaultBlockState(), Block.UPDATE_CLIENTS);
            loot.stockEvacuationCache(world, cache, profile);
        }
    }

    // -----------------------------------------------------------------------
    // Gate defence
    // -----------------------------------------------------------------------

    public void maybeGateDefense(ServerLevel world, CityAnchor anchor, CityProfile profile,
            long cityKey, long gameTime) {
        long next = nextGateDefenseTick.getOrDefault(cityKey, 0L);
        if (gameTime < next) return;
        nextGateDefenseTick.put(cityKey, gameTime + IceWallConfig.CITY_GATE_DEFENSE_INTERVAL_TICKS);
        Random random = new Random(anchor.seed() ^ gameTime ^ 0xDEFEC7EDL);
        if (random.nextInt(IceWallConfig.CITY_GATE_DEFENSE_CHANCE) != 0) return;

        int centerX = anchor.chunkX() * 16 + 8;
        int centerZ = anchor.chunkZ() * 16 + 8;
        BlockPos gate = evacuationGate(world, centerX, centerZ, anchor.seed());
        int spawned = 0;
        for (int i = 0; i < IceWallConfig.CITY_GATE_DEFENSE_MAX_MOBS; i++) {
            int side = random.nextBoolean() ? 1 : -1;
            int x    = gate.getX() + side * (5 + random.nextInt(7));
            int z    = gate.getZ() - 5 + random.nextInt(11);
            int y    = CityBlockBuilder.surfaceY(world, x, z);
            var raider = EntityType.STRAY.create(world, EntitySpawnReason.NATURAL);
            if (raider == null) continue;
            raider.teleportTo(x + 0.5, y, z + 0.5);
            raider.setCustomName(Component.literal("Gate Frost Raider").withStyle(ChatFormatting.AQUA));
            raider.setPersistenceRequired();
            world.addFreshEntity(raider);
            spawned++;
        }
        if (spawned > 0) {
            world.playSound(null, gate, SoundEvents.WITHER_SKELETON_AMBIENT, SoundSource.HOSTILE, 0.8f, 0.7f);
            warnPlayers(world, gate, profile);
        }
    }

    private void warnPlayers(ServerLevel world, BlockPos gate, CityProfile profile) {
        Component warning = Component.literal("Gate defense - " + profile.name() + ": frozen raiders at the evacuation platform.")
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
        for (ServerPlayer player : world.players()) {
            if (player.blockPosition().distManhattan(gate) <= IceWallConfig.CITY_GATE_DEFENSE_ANNOUNCEMENT_DISTANCE)
                player.connection.send(new ClientboundSetActionBarTextPacket(warning));
        }
    }

    // -----------------------------------------------------------------------
    // Signal fires
    // -----------------------------------------------------------------------

    public void lightSignalFires(ServerLevel world, CityAnchor anchor, long cityKey) {
        if (!litSignalFires.add(cityKey)) return;
        int centerX = anchor.chunkX() * 16 + 8;
        int centerZ = anchor.chunkZ() * 16 + 8;
        int radius  = IceWallConfig.CITY_RADIUS_CHUNKS * 16;
        CityBlockBuilder.lightSignalFires(world, centerX, centerZ, radius);
        world.playSound(null, new BlockPos(centerX, CityBlockBuilder.surfaceY(world, centerX, centerZ), centerZ),
                SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS,
                1.4f, 0.8f + new Random(cityKey).nextFloat() * 0.3f);
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    public static BlockPos evacuationGate(ServerLevel world, int centerX, int centerZ, long seed) {
        int offset = IceWallConfig.CITY_RADIUS_CHUNKS * 16 + 4;
        int gateX  = (seed & 1L) == 0L ? centerX + offset : centerX - offset;
        int gateZ  = centerZ + 2;
        int gateY  = CityBlockBuilder.surfaceY(world, gateX, gateZ);
        return new BlockPos(gateX, gateY, gateZ);
    }
}
