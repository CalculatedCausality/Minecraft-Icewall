package dev.icewall.wall;

import dev.icewall.city.CityBlockBuilder;
import dev.icewall.city.CityDomain;
import dev.icewall.city.CityDomain.BuildingType;
import dev.icewall.city.CityDomain.CityAnchor;
import dev.icewall.city.CityDomain.CityProfile;
import dev.icewall.city.CityDomain.CityStyle;
import dev.icewall.city.CityEventManager;
import dev.icewall.city.CityLootHelper;
import dev.icewall.config.IceWallConfig;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Procedural survivor-city generator - thin orchestrator.
 *
 * Heavy lifting is delegated to:
 *   CityDomain       - enums, records, seed/name helpers, anchor lookup
 *   CityBlockBuilder - all block placement and material selection
 *   CityLootHelper   - loot stocking, villager spawning, written books
 *   CityEventManager - evacuation, gate defence, signal fires
 */
public final class SurvivorCityGenerator {

    // Per-player runtime state
    private final Map<UUID, Long> lastCityByPlayer          = new HashMap<>();
    private final Map<UUID, Long> nextBulletinTick          = new HashMap<>();
    private final Map<UUID, Long> nextEvacuationWarningTick = new HashMap<>();

    // Per-chunk generation guards
    private final Set<Long> generatedChunks    = new HashSet<>();
    private final Set<Long> frozenRuinedChunks = new HashSet<>();

    // Helpers
    private final CityLootHelper   loot   = new CityLootHelper();
    private final CityEventManager events = new CityEventManager(loot);

    // -----------------------------------------------------------------------
    // Server tick
    // -----------------------------------------------------------------------

    public void tick(ServerLevel world, IceWallState state) {
        if (!state.isActive()) return;
        long gameTime = world.getGameTime();
        if (gameTime % IceWallConfig.CITY_PLAYER_SCAN_INTERVAL_TICKS != 0L) return;

        int wallZ = state.getWallFrontZ();
        for (ServerPlayer player : world.players()) {
            if (player.isSpectator()) continue;
            ChunkPos playerChunk = ChunkPos.containing(player.blockPosition());
            CityAnchor anchor = CityDomain.nearestAnchor(world, playerChunk.x(), playerChunk.z());
            int dx        = playerChunk.x() - anchor.chunkX();
            int dz        = playerChunk.z() - anchor.chunkZ();
            int distAhead = player.blockPosition().getZ() - wallZ;

            boolean insideCity = Math.abs(dx) <= IceWallConfig.CITY_RADIUS_CHUNKS
                    && Math.abs(dz) <= IceWallConfig.CITY_RADIUS_CHUNKS
                    && distAhead >= IceWallConfig.CITY_MIN_DISTANCE_AHEAD - 64
                    && distAhead <= IceWallConfig.CITY_MAX_DISTANCE_AHEAD + 64;
            if (!insideCity) continue;

            long cityKey    = CityDomain.cityKey(anchor);
            CityProfile profile = CityDomain.profileFor(anchor);
            UUID playerId   = player.getUUID();

            if (lastCityByPlayer.getOrDefault(playerId, Long.MIN_VALUE) != cityKey) {
                lastCityByPlayer.put(playerId, cityKey);
                nextBulletinTick.put(playerId, gameTime + 80L);
                nextEvacuationWarningTick.put(playerId, gameTime + 40L);
                player.connection.send(new ClientboundSetActionBarTextPacket(
                        Component.literal("Entering " + profile.name() + " - "
                                + CityDomain.districtLabel(profile.district())
                                + " / " + CityDomain.styleLabel(profile.style()))
                                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
                continue;
            }

            long nextWarning = nextEvacuationWarningTick.getOrDefault(playerId, 0L);
            if (distAhead <= IceWallConfig.CITY_EVACUATION_WARNING_DISTANCE && gameTime >= nextWarning) {
                nextEvacuationWarningTick.put(playerId, gameTime + IceWallConfig.CITY_EVACUATION_WARNING_INTERVAL_TICKS);
                nextBulletinTick.put(playerId, gameTime + IceWallConfig.CITY_BULLETIN_INTERVAL_TICKS / 2L);
                events.rallyResidents(world, anchor, profile, cityKey, gameTime);
                events.maybeGateDefense(world, anchor, profile, cityKey, gameTime);
                events.lightSignalFires(world, anchor, cityKey);
                player.connection.send(new ClientboundSetActionBarTextPacket(
                        Component.literal("Evacuation bells - " + profile.name()
                                + ": follow the rail south and empty the caches.")
                                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
                continue;
            }

            long next = nextBulletinTick.getOrDefault(playerId, 0L);
            if (gameTime >= next) {
                nextBulletinTick.put(playerId, gameTime + IceWallConfig.CITY_BULLETIN_INTERVAL_TICKS);
                player.connection.send(new ClientboundSetActionBarTextPacket(
                        Component.literal("Bulletin - " + profile.name() + ": " + bulletinFor(profile, wallZ, player))
                                .withStyle(ChatFormatting.YELLOW)));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Chunk load
    // -----------------------------------------------------------------------

    public void onChunkLoad(ServerLevel world, IceWallState state, LevelChunk chunk) {
        if (!state.isActive()) return;
        ChunkPos pos = chunk.getPos();
        long key     = pos.pack();

        int wallZ        = state.getWallFrontZ();
        int chunkCenterZ = pos.getMiddleBlockZ();
        int distAhead    = chunkCenterZ - wallZ;

        CityAnchor anchor = CityDomain.nearestAnchor(world, pos.x(), pos.z());
        int dx = pos.x() - anchor.chunkX();
        int dz = pos.z() - anchor.chunkZ();
        boolean cityChunk = Math.abs(dx) <= IceWallConfig.CITY_RADIUS_CHUNKS
                && Math.abs(dz) <= IceWallConfig.CITY_RADIUS_CHUNKS;

        if (cityChunk && distAhead <= IceWallConfig.CITY_RUIN_START_DISTANCE) {
            if (frozenRuinedChunks.add(key)) {
                CityProfile profile = CityDomain.profileFor(anchor);
                Random random = new Random(anchor.seed() ^ (pos.pack() * 97L) ^ 0xF7057EDC17EL);
                freezeCityChunk(world, pos, profile, random);
            }
            return;
        }

        if (!generatedChunks.add(key)) return;
        if (distAhead < IceWallConfig.CITY_MIN_DISTANCE_AHEAD
                || distAhead > IceWallConfig.CITY_MAX_DISTANCE_AHEAD) return;
        if (!cityChunk) return;

        Random random = new Random(anchor.seed() ^ (pos.pack() * 31L));
        CityProfile profile = CityDomain.profileFor(anchor);
        generateCityChunk(world, pos, dx, dz, profile, random);
    }

    // -----------------------------------------------------------------------
    // Generation
    // -----------------------------------------------------------------------

    private void generateCityChunk(ServerLevel world, ChunkPos pos, int dx, int dz,
            CityProfile profile, Random random) {
        CityStyle style = profile.style();
        int minX    = pos.getMinBlockX();
        int minZ    = pos.getMinBlockZ();
        int centerX = pos.getMiddleBlockX();
        int centerZ = pos.getMiddleBlockZ();

        CityBlockBuilder.buildStreetCross(world, minX, minZ, style);
        CityBlockBuilder.buildPerimeterWalls(world, minX, minZ, dx, dz, style);

        if (dx == 0 && dz == 0) {
            CityBlockBuilder.buildCentralPlaza(world, centerX, centerZ, profile, loot);
            return;
        }

        boolean cardinal = dx == 0 || dz == 0;
        if (!cardinal && random.nextInt(3) == 0) {
            CityBlockBuilder.buildPocketPark(world, centerX, centerZ, style, random);
            return;
        }

        BuildingType type = CityDomain.chooseBuilding(dx, dz, profile.district(), random);
        int sizeX   = 6 + random.nextInt(4);
        int sizeZ   = 6 + random.nextInt(4);
        int originX = minX + 3 + random.nextInt(Math.max(1, 10 - sizeX));
        int originZ = minZ + 3 + random.nextInt(Math.max(1, 10 - sizeZ));
        if (cardinal) {
            if (dx == 0) originX = minX + 9;
            if (dz == 0) originZ = minZ + 9;
        }
        CityBlockBuilder.buildBuilding(world, originX, originZ, sizeX, sizeZ, type, profile, random, loot);
    }

    // -----------------------------------------------------------------------
    // Frozen ruin conversion
    // -----------------------------------------------------------------------

    private void freezeCityChunk(ServerLevel world, ChunkPos pos, CityProfile profile, Random random) {
        int minX = pos.getMinBlockX(), minZ = pos.getMinBlockZ();
        int changed = 0;
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int x = minX + localX, z = minZ + localZ;
                int topY = CityBlockBuilder.surfaceY(world, x, z);
                int minY = Math.max(world.getMinY(), topY - 8);
                int maxY = Math.min(world.getMaxY() - 1, topY + 24);
                for (int y = minY; y <= maxY; y++) {
                    BlockPos blockPos = new BlockPos(x, y, z);
                    BlockState state  = world.getBlockState(blockPos);
                    BlockState frozen = CityBlockBuilder.frozenStateFor(state, profile.district(), random);
                    if (frozen != null) {
                        world.setBlock(blockPos, frozen, Block.UPDATE_CLIENTS);
                        changed++;
                    } else if (state.is(Blocks.LAVA)) {
                        world.setBlock(blockPos, Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_CLIENTS);
                        changed++;
                    }
                }
                if (random.nextInt(5) == 0) {
                    world.setBlock(new BlockPos(x, topY, z), Blocks.SNOW.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        if (changed > 16) {
            buildRuinMarker(world, pos.getMiddleBlockX(), pos.getMiddleBlockZ(), profile);
            spawnAftermathPatrol(world, pos, profile, random);
        }
    }

    private void buildRuinMarker(ServerLevel world, int x, int z, CityProfile profile) {
        int y = CityBlockBuilder.surfaceY(world, x, z);
        world.setBlock(new BlockPos(x,     y,     z), Blocks.BLUE_ICE.defaultBlockState(),  Block.UPDATE_CLIENTS);
        world.setBlock(new BlockPos(x,     y + 1, z), Blocks.IRON_BARS.defaultBlockState(), Block.UPDATE_CLIENTS);
        world.setBlock(new BlockPos(x,     y + 2, z), Blocks.BELL.defaultBlockState(),      Block.UPDATE_CLIENTS);
        BlockPos cache = new BlockPos(x + 1, y, z);
        world.setBlock(cache, Blocks.BARREL.defaultBlockState(), Block.UPDATE_CLIENTS);
        loot.stockFrozenCache(world, cache, profile);
    }

    private void spawnAftermathPatrol(ServerLevel world, ChunkPos pos, CityProfile profile, Random random) {
        for (int i = 0; i < 1 + random.nextInt(2); i++) {
            int x = pos.getMinBlockX() + 4 + random.nextInt(8);
            int z = pos.getMinBlockZ() + 4 + random.nextInt(8);
            int y = CityBlockBuilder.surfaceY(world, x, z);
            var stray = EntityType.STRAY.create(world, EntitySpawnReason.NATURAL);
            if (stray == null) continue;
            stray.teleportTo(x + 0.5, y, z + 0.5);
            stray.setCustomName(Component.literal("Frozen " + CityDomain.districtLabel(profile.district()) + " Patrol")
                    .withStyle(ChatFormatting.AQUA));
            stray.setPersistenceRequired();
            world.addFreshEntity(stray);
        }
    }

    // -----------------------------------------------------------------------
    // Bulletin text
    // -----------------------------------------------------------------------

    private static String bulletinFor(CityProfile profile, int wallZ, ServerPlayer player) {
        int dist       = player.blockPosition().getZ() - wallZ;
        String urgency = dist < 350 ? "Glacier warning: " : "Work order: ";
        return urgency + switch (profile.district()) {
            case FORGEWARD   -> "feed coal to the forge caches and repair damaged tools.";
            case GARDENWARD  -> "move seed stores south before the frost reaches the beds.";
            case ARCHIVEWARD -> "copy maps, secure books, and mark the rail route.";
            case MARKETWARD  -> "sort trade crates for the next evacuation convoy.";
            case CITADEL     -> "inspect gate walls and keep arrows near the watchtower.";
        };
    }
}
