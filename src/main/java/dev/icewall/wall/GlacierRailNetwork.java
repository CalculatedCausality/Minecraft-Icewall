package dev.icewall.wall;

import dev.icewall.city.CityDomain;
import dev.icewall.city.CityDomain.CityAnchor;
import dev.icewall.config.IceWallConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Perpetual glacier-escape rail network.
 *
 * A single-track rail line runs southward, continuously extended by a crew of
 * villager workers who flee just ahead of the advancing glacier wall.  The line
 * gently meanders with the landscape and throws up rough bridges over water,
 * ravines, and steep drops.  Storage minecarts stocked with survival supplies
 * are periodically dispatched along the line so players can deposit belongings
 * and retrieve aid.
 *
 * Behaviour overview:
 *  - Lays RAIL blocks along a gradually wandering route
 *  - Pads the ground under each rail with COBBLESTONE or bridge decking
 *  - Builds plank bridges with fence posts when terrain becomes unsafe
 *  - Adds powered booster sections, waystation platforms, parked storage carts,
 *    and periodic rail repair passes
 *  - Extends the rail head by RAIL_EXTEND_BATCH_SIZE blocks every
 *    RAIL_EXTEND_INTERVAL_TICKS, always staying RAIL_MIN_AHEAD_DISTANCE blocks
 *    ahead of the glacier face
 *  - Every RAIL_MINECART_INTERVAL_TICKS, spawns a chest minecart stocked with
 *    4-8 random survival items and announces it to nearby players
 *  - Maintains RAIL_WORKER_COUNT villager "Rail Crew" workers near the head;
 *    workers are invulnerable and teleported back if they wander too far
 */
public final class GlacierRailNetwork {

    // Sentinel — railHeadZ before first tick
    private static final int UNINITIALISED = Integer.MIN_VALUE;

    private int railHeadZ = UNINITIALISED;
    private int railBaseX = UNINITIALISED;
    private int railHeadX = UNINITIALISED;
    private int railHeadY = UNINITIALISED;

    /** UUIDs of spawned rail-crew villagers — now managed by RailWorkerCrew. */
    private final RailWorkerCrew crew = new RailWorkerCrew();

    /** Z positions where supply carts have already been placed (prevents doubles). */
    private final Set<Integer> usedCartZ = new HashSet<>();

    /** Z positions where a waystation has already been built. */
    private final Set<Integer> builtStations = new HashSet<>();

    /** City anchors that already have a rail spur/station. */
    private final Set<Long> builtCitySpurs = new HashSet<>();

    private int repairCursorZ = UNINITIALISED;

    private final Random rng = new Random();

    // -----------------------------------------------------------------------
    // Public tick entry point
    // -----------------------------------------------------------------------

    public void tick(ServerLevel world, IceWallState state) {
        if (!state.isActive()) return;

        int wallZ = state.getWallFrontZ();

        // First-tick initialisation: anchor the rail X to the world centre and
        // seed the head well ahead of the starting wall position.
        if (railHeadZ == UNINITIALISED) {
                railBaseX = (state.getMinExploredX() + state.getMaxExploredX()) / 2;
                railHeadX = railBaseX;
                railHeadZ = wallZ + IceWallConfig.RAIL_MIN_AHEAD_DISTANCE;
                railHeadY = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    railHeadX, railHeadZ);
        }

        long tick = world.getGameTime();

        // Safety: if the wall has nearly caught the head, extend it immediately
        if (railHeadZ - wallZ < IceWallConfig.RAIL_MIN_AHEAD_DISTANCE) {
            extendRail(world, wallZ + IceWallConfig.RAIL_MIN_AHEAD_DISTANCE
                    + IceWallConfig.RAIL_EXTEND_BATCH_SIZE);
        }

        // Scheduled extension — keeps a generous working buffer ahead
        if (tick % IceWallConfig.RAIL_EXTEND_INTERVAL_TICKS == 0L) {
            extendRail(world, railHeadZ + IceWallConfig.RAIL_EXTEND_BATCH_SIZE);
        }

        // Periodic supply minecart dispatch
        if (tick % IceWallConfig.RAIL_MINECART_INTERVAL_TICKS == 0L) {
            dispatchSupplyCart(world, wallZ);
        }

        // Worker crew management
        if (tick % IceWallConfig.RAIL_WORKER_MANAGE_INTERVAL_TICKS == 0L) {
            crew.tick(world, wallZ, railHeadX, railHeadZ);
        }

        // Maintenance crews periodically patch rail segments that were broken,
        // buried, or overwritten by other glacier systems.
        if (tick % IceWallConfig.RAIL_REPAIR_INTERVAL_TICKS == 0L) {
            repairRailNetwork(world, wallZ);
        }

        // Connect the main line to nearby generated survivor cities with short
        // lateral spurs and gate stations once the head has passed them.
        if (tick % IceWallConfig.RAIL_CITY_SPUR_INTERVAL_TICKS == 0L) {
            connectNearbyCityGates(world, wallZ);
        }
    }

    // -----------------------------------------------------------------------
    // Rail extension
    // -----------------------------------------------------------------------

    /**
     * Lays rail from the current rail head up to (but not including) {@code targetZ}.
     * The route follows a slow wave so it does not read as a ruler-straight line.
     * When the desired route changes X, a short east/west connector is placed at
     * the current Z before the line continues south.  Each rail segment decides
     * whether it can sit on terrain or needs a bridge deck and support posts.
     */
    private void extendRail(ServerLevel world, int targetZ) {
        if (targetZ <= railHeadZ) return;

        for (int z = railHeadZ; z < targetZ; z++) {
            int targetX = desiredRailXForZ(z);
            if (world.getChunk(railHeadX >> 4, z >> 4, ChunkStatus.FULL, false) == null
                    || world.getChunk(targetX >> 4, z >> 4, ChunkStatus.FULL, false) == null) {
                // Unloaded — stop and resume next call
                railHeadZ = z;
                return;
            }

            if (targetX != railHeadX) {
                int step = targetX > railHeadX ? 1 : -1;
                while (railHeadX != targetX) {
                    railHeadX += step;
                    placeRouteRail(world, railHeadX, z, true);
                }
            }

            placeRouteRail(world, railHeadX, z, false);

            if (z > 0 && z % IceWallConfig.RAIL_STATION_INTERVAL_BLOCKS == 0) {
                buildWaystation(world, railHeadX, railHeadY, z);
            }
        }

        // Light audio cue: hammer-on-metal at the new head
        BlockPos headPos = new BlockPos(railHeadX, railHeadY, targetZ - 1);
        world.playSound(null, headPos,
                SoundEvents.ANVIL_HIT, SoundSource.BLOCKS,
                0.5f, 0.7f + rng.nextFloat() * 0.4f);

        railHeadZ = targetZ;
    }

    // -----------------------------------------------------------------------
    // Supply minecart dispatch
    // -----------------------------------------------------------------------

    /**
     * Spawns a chest minecart on the rail at RAIL_CART_SPAWN_OFFSET blocks ahead
     * of the wall, stocks it with survival supplies, and notifies nearby players.
     */
    private void dispatchSupplyCart(ServerLevel world, int wallZ) {
        int baseZ = wallZ + IceWallConfig.RAIL_CART_SPAWN_OFFSET;
        if (baseZ >= railHeadZ) return;

        // Find an unused Z slot near the target
        int cartZ = baseZ;
        for (int attempt = 0; attempt < 20; attempt++) {
            if (!usedCartZ.contains(cartZ)) break;
            cartZ = baseZ + (attempt + 1) * 5;
        }
        if (usedCartZ.contains(cartZ)) return;
        if (cartZ >= railHeadZ) return;

        int x = findRailXNear(cartZ);
        if (world.getChunk(x >> 4, cartZ >> 4, ChunkStatus.FULL, false) == null) return;

        int railY = findRailY(world, x, cartZ);
        if (railY < 0) return; // no rail laid here yet

        MinecartChest cart = EntityType.CHEST_MINECART.create(world, EntitySpawnReason.NATURAL);
        if (cart == null) return;

        cart.setPos(x + 0.5, railY + 0.0625, cartZ + 0.5);
        stockCart(cart);
        world.addFreshEntity(cart);
        usedCartZ.add(cartZ);

        // Sound + announcement
        BlockPos cartPos = new BlockPos(x, railY, cartZ);
        world.playSound(null, cartPos,
                SoundEvents.MINECART_RIDING, SoundSource.NEUTRAL, 1.0f, 0.75f);

        Component msg = Component.literal("  \u2744 Rail crew dispatched a supply cart (Z=" + cartZ + ")")
                .withStyle(ChatFormatting.YELLOW);
        for (ServerPlayer player : world.players()) {
            int playerDist = player.blockPosition().getZ() - wallZ;
            if (playerDist >= -20 && playerDist <= IceWallConfig.RAIL_ANNOUNCEMENT_DISTANCE) {
                player.connection.send(new ClientboundSetActionBarTextPacket(msg));
            }
        }
    }

    /** Stock a chest minecart with 4–8 random survival supplies. */
    private void stockCart(MinecartChest cart) {
        List<ItemStack> pool = new ArrayList<>(Arrays.asList(
                new ItemStack(Items.BREAD,             8),
                new ItemStack(Items.COOKED_BEEF,       4),
                new ItemStack(Items.IRON_INGOT,        8),
                new ItemStack(Items.TORCH,            32),
                new ItemStack(Items.OAK_PLANKS,       16),
                new ItemStack(Items.ARROW,            24),
                new ItemStack(Items.IRON_PICKAXE,      1),
                new ItemStack(Items.LEATHER_CHESTPLATE,1),
                new ItemStack(Items.COAL,             16),
                new ItemStack(Items.COOKED_PORKCHOP,   6),
                new ItemStack(Items.SNOWBALL,         16),
                new ItemStack(Items.RAIL,             16)
        ));
        Collections.shuffle(pool, rng);

        int count = 4 + rng.nextInt(5); // 4–8 stacks
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < cart.getContainerSize(); i++) slots.add(i);
        Collections.shuffle(slots, rng);

        for (int i = 0; i < count && i < slots.size(); i++) {
            cart.setItem(slots.get(i), pool.get(i % pool.size()).copy());
        }
    }

    // -----------------------------------------------------------------------
    // Worker crew management
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private int desiredRailXForZ(int z) {
        double waveA = Math.sin(z / (double) IceWallConfig.RAIL_MEANDER_WAVELENGTH);
        double waveB = Math.sin((z + 91) / (double) (IceWallConfig.RAIL_MEANDER_WAVELENGTH * 2));
        int offset = (int) Math.round((waveA * 0.65 + waveB * 0.35)
                * IceWallConfig.RAIL_MEANDER_AMPLITUDE);
        return railBaseX + offset;
    }

    private int findRailXNear(int z) {
        int guess = desiredRailXForZ(z);
        int bestX = guess;
        int bestDistance = Integer.MAX_VALUE;
        for (int x = guess - IceWallConfig.RAIL_MEANDER_AMPLITUDE - 4;
                x <= guess + IceWallConfig.RAIL_MEANDER_AMPLITUDE + 4; x++) {
            int distance = Math.abs(x - guess);
            if (distance >= bestDistance) continue;
            bestDistance = distance;
            bestX = x;
        }
        return bestX;
    }

    private void placeRouteRail(ServerLevel world, int x, int z, boolean connector) {
        int surfaceY = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos groundPos = new BlockPos(x, surfaceY - 1, z);
        BlockState groundState = world.getBlockState(groundPos);

        boolean waterCrossing = !groundState.getFluidState().isEmpty();
        boolean steepTerrain = railHeadY != UNINITIALISED
                && Math.abs(surfaceY - railHeadY) > IceWallConfig.RAIL_MAX_NATURAL_GRADE;
        boolean bridge = waterCrossing || steepTerrain || !groundState.isSolid();

        int railY = surfaceY;
        if (railHeadY != UNINITIALISED) {
            if (bridge) {
                int targetY = Math.max(surfaceY + 1, railHeadY);
                railY = moveToward(railHeadY, targetY, 1);
            } else {
                railY = moveToward(railHeadY, surfaceY, 1);
            }
        }

        BlockPos railPos = new BlockPos(x, railY, z);
        BlockPos deckPos = railPos.below();

        if (bridge || railY > surfaceY) {
            buildBridgeDeck(world, deckPos, surfaceY, connector);
        } else if (!world.getBlockState(deckPos).isSolid()) {
            world.setBlock(deckPos, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }

        clearRailSpace(world, railPos);
        if (!connector && z % IceWallConfig.RAIL_POWERED_INTERVAL_BLOCKS == 0) {
            world.setBlock(deckPos, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
            world.setBlock(railPos, Blocks.POWERED_RAIL.defaultBlockState()
                    .setValue(PoweredRailBlock.POWERED, true), Block.UPDATE_CLIENTS);
        } else {
            world.setBlock(railPos, Blocks.RAIL.defaultBlockState(), Block.UPDATE_CLIENTS);
        }

        railHeadX = x;
        railHeadY = railY;
    }

    // -----------------------------------------------------------------------
    // Waystations, sidings, and repairs
    // -----------------------------------------------------------------------

    private void buildWaystation(ServerLevel world, int trackX, int trackY, int z) {
        int stationKey = z / IceWallConfig.RAIL_STATION_INTERVAL_BLOCKS;
        if (!builtStations.add(stationKey)) return;
        if (world.getChunk(trackX >> 4, z >> 4, ChunkStatus.FULL, false) == null) return;

        int side = stationKey % 2 == 0 ? 1 : -1;
        int platformY = trackY - 1;

        // Short siding rail from the main line onto the platform.
        for (int offset = 1; offset <= IceWallConfig.RAIL_STATION_SIDING_LENGTH; offset++) {
            BlockPos deck = new BlockPos(trackX + side * offset, platformY, z);
            placeStationDeck(world, deck);
            BlockPos rail = deck.above();
            clearRailSpace(world, rail);
            world.setBlock(rail, Blocks.RAIL.defaultBlockState(), Block.UPDATE_CLIENTS);
        }

        // A small timber platform around the end of the siding.
        int centreX = trackX + side * (IceWallConfig.RAIL_STATION_SIDING_LENGTH + 1);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos deck = new BlockPos(centreX + dx, platformY, z + dz);
                placeStationDeck(world, deck);
            }
        }

        BlockPos chestPos = new BlockPos(centreX, trackY, z + 2);
        if (world.getBlockState(chestPos).isAir()) {
            world.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS);
            stockWaystationChest(world, chestPos);
        }

        placeStationTorch(world, new BlockPos(centreX - 2, trackY, z - 2));
        placeStationTorch(world, new BlockPos(centreX + 2, trackY, z - 2));
        placeStationTorch(world, new BlockPos(centreX - 2, trackY, z + 2));
        placeStationTorch(world, new BlockPos(centreX + 2, trackY, z + 2));

        spawnParkedStorageCart(world, trackX + side * IceWallConfig.RAIL_STATION_SIDING_LENGTH, trackY, z);

        world.playSound(null, chestPos, SoundEvents.VILLAGER_WORK_TOOLSMITH,
                SoundSource.NEUTRAL, 0.9f, 0.8f + rng.nextFloat() * 0.2f);
    }

    private void placeStationDeck(ServerLevel world, BlockPos deck) {
        RailTrackLayer.placeStationDeck(world, deck);
    }

    private void placeStationTorch(ServerLevel world, BlockPos pos) {
        RailTrackLayer.placeStationTorch(world, pos);
    }

    private void stockWaystationChest(ServerLevel world, BlockPos chestPos) {
        BlockEntity be = world.getBlockEntity(chestPos);
        if (!(be instanceof BaseContainerBlockEntity chest)) return;
        ItemStack[] goods = {
                new ItemStack(Items.RAIL, 24),
                new ItemStack(Items.POWERED_RAIL, 6),
                new ItemStack(Items.REDSTONE_TORCH, 8),
                new ItemStack(Items.OAK_PLANKS, 32),
                new ItemStack(Items.COBBLESTONE, 32),
                new ItemStack(Items.BREAD, 8),
                new ItemStack(Items.COAL, 12),
                new ItemStack(Items.MINECART, 1)
        };
        for (int i = 0; i < goods.length && i < chest.getContainerSize(); i++) {
            chest.setItem(i, goods[i].copy());
        }
    }

    private void spawnParkedStorageCart(ServerLevel world, int x, int y, int z) {
        MinecartChest cart = EntityType.CHEST_MINECART.create(world, EntitySpawnReason.NATURAL);
        if (cart == null) return;
        cart.setPos(x + 0.5, y + 0.0625, z + 0.5);
        stockCart(cart);
        world.addFreshEntity(cart);
    }

    private void repairRailNetwork(ServerLevel world, int wallZ) {
        if (repairCursorZ == UNINITIALISED || repairCursorZ < wallZ + 2 || repairCursorZ >= railHeadZ) {
            repairCursorZ = wallZ + 2;
        }
        int limit = Math.min(railHeadZ, repairCursorZ + IceWallConfig.RAIL_REPAIR_BATCH_SIZE);
        for (int z = repairCursorZ; z < limit; z++) {
            int x = desiredRailXForZ(z);
            if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) continue;
            int y = findRailY(world, x, z);
            if (y >= 0) continue;
            placeRepairRail(world, x, z);
        }
        repairCursorZ = limit;
    }

    private void connectNearbyCityGates(ServerLevel world, int wallZ) {
        if (railHeadZ == UNINITIALISED) return;
        int startZ = Math.max(wallZ + IceWallConfig.CITY_MIN_DISTANCE_AHEAD,
                railHeadZ - IceWallConfig.RAIL_CITY_SPUR_SCAN_BACK_BLOCKS);
        int endZ = Math.min(railHeadZ - 8, wallZ + IceWallConfig.CITY_MAX_DISTANCE_AHEAD);
        for (int z = startZ; z <= endZ; z += 16) {
            CityAnchor anchor = nearestCityAnchor(world, railBaseX >> 4, z >> 4);
            long cityKey = cityKey(anchor);
            if (builtCitySpurs.contains(cityKey)) continue;

            int cityCenterX = anchor.chunkX() * 16 + 8;
            int cityCenterZ = anchor.chunkZ() * 16 + 8;
            if (Math.abs(cityCenterZ - z) > 24) continue;
            if (cityCenterZ <= wallZ + IceWallConfig.RAIL_CITY_SPUR_MIN_AHEAD_DISTANCE
                    || cityCenterZ >= railHeadZ - 8) continue;

            int mainX = desiredRailXForZ(cityCenterZ);
            int gateOffset = IceWallConfig.CITY_RADIUS_CHUNKS * 16;
            int gateX = cityCenterX + (mainX < cityCenterX ? -gateOffset : gateOffset);
            int distance = Math.abs(mainX - gateX);
            if (distance < 6 || distance > IceWallConfig.RAIL_CITY_SPUR_MAX_DISTANCE) continue;
            if (!canBuildCitySpur(world, mainX, gateX, cityCenterZ)) continue;
            if (findRailY(world, mainX, cityCenterZ) < 0) continue;

            buildCitySpur(world, mainX, gateX, cityCenterZ, cityKey);
            break;
        }
    }

    private void buildCitySpur(ServerLevel world, int mainX, int gateX, int z, long cityKey) {
        int step = gateX > mainX ? 1 : -1;
        int previousY = findRailY(world, mainX, z);
        for (int x = mainX + step; x != gateX + step; x += step) {
            previousY = placeCitySpurRail(world, x, z, previousY, x == gateX);
        }
        buildCityGateStation(world, gateX, previousY, z, step);
        builtCitySpurs.add(cityKey);

        BlockPos stationPos = new BlockPos(gateX, previousY, z);
        world.playSound(null, stationPos, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS,
                0.9f, 1.1f + rng.nextFloat() * 0.2f);
        Component msg = Component.literal("Rail crew opened a city gate spur at Z=" + z)
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
        for (ServerPlayer player : world.players()) {
            if (player.blockPosition().distManhattan(stationPos) <= IceWallConfig.RAIL_ANNOUNCEMENT_DISTANCE) {
                player.connection.send(new ClientboundSetActionBarTextPacket(msg));
            }
        }
    }

    private int placeCitySpurRail(ServerLevel world, int x, int z, int previousY, boolean poweredStop) {
        int surfaceY = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int railY = previousY == UNINITIALISED ? surfaceY : moveToward(previousY, surfaceY, 1);
        BlockPos railPos = new BlockPos(x, railY, z);
        BlockPos deckPos = railPos.below();
        if (!world.getBlockState(deckPos).isSolid()) {
            buildBridgeDeck(world, deckPos, surfaceY, true);
        } else {
            world.setBlock(deckPos, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        clearRailSpace(world, railPos);
        if (poweredStop) {
            world.setBlock(deckPos, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
            world.setBlock(railPos, Blocks.POWERED_RAIL.defaultBlockState()
                    .setValue(PoweredRailBlock.POWERED, true), Block.UPDATE_CLIENTS);
        } else {
            world.setBlock(railPos, Blocks.RAIL.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        return railY;
    }

    private void buildCityGateStation(ServerLevel world, int gateX, int railY, int z, int outwardStep) {
        int platformY = railY - 1;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                BlockPos deck = new BlockPos(gateX + dx, platformY, z + dz);
                placeStationDeck(world, deck);
            }
        }
        for (int dz = -1; dz <= 1; dz++) {
            BlockPos rail = new BlockPos(gateX + outwardStep, railY, z + dz);
            placeStationDeck(world, rail.below());
            clearRailSpace(world, rail);
            world.setBlock(rail, Blocks.RAIL.defaultBlockState(), Block.UPDATE_CLIENTS);
        }

        BlockPos chestPos = new BlockPos(gateX, railY, z + 3);
        world.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS);
        stockCityGateChest(world, chestPos);
        BlockPos bellPos = new BlockPos(gateX - outwardStep, railY + 1, z - 3);
        world.setBlock(bellPos.below(), Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_CLIENTS);
        world.setBlock(bellPos, Blocks.BELL.defaultBlockState(), Block.UPDATE_CLIENTS);
        placeStationTorch(world, new BlockPos(gateX - 2, railY, z - 2));
        placeStationTorch(world, new BlockPos(gateX + 2, railY, z - 2));
        spawnParkedStorageCart(world, gateX + outwardStep, railY, z);
    }

    private void stockCityGateChest(ServerLevel world, BlockPos chestPos) {
        BlockEntity be = world.getBlockEntity(chestPos);
        if (!(be instanceof BaseContainerBlockEntity chest)) return;
        ItemStack[] goods = {
                new ItemStack(Items.MINECART, 2),
                new ItemStack(Items.CHEST_MINECART, 1),
                new ItemStack(Items.RAIL, 32),
                new ItemStack(Items.POWERED_RAIL, 8),
                new ItemStack(Items.REDSTONE_TORCH, 8),
                new ItemStack(Items.BREAD, 12),
                new ItemStack(Items.COAL, 16),
                new ItemStack(Items.BARREL, 4)
        };
        for (int i = 0; i < goods.length && i < chest.getContainerSize(); i++) {
            chest.setItem(i, goods[i].copy());
        }
    }

    private boolean canBuildCitySpur(ServerLevel world, int mainX, int gateX, int z) {
        int step = gateX > mainX ? 1 : -1;
        for (int x = mainX; x != gateX + step; x += step) {
            if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) return false;
        }
        return true;
    }

    private CityAnchor nearestCityAnchor(ServerLevel world, int chunkX, int chunkZ) {
        return CityDomain.nearestAnchor(world, chunkX, chunkZ);
    }

    private long cityKey(CityAnchor anchor) {
        return CityDomain.cityKey(anchor);
    }

    private void placeRepairRail(ServerLevel world, int x, int z) {
        RailTrackLayer.placeRepairRail(world, x, z);
    }

    private int moveToward(int current, int target, int maxStep) {
        return RailTrackLayer.moveToward(current, target, maxStep);
    }

    private void clearRailSpace(ServerLevel world, BlockPos railPos) {
        RailTrackLayer.clearRailSpace(world, railPos);
    }

    private void buildBridgeDeck(ServerLevel world, BlockPos deckPos, int surfaceY, boolean connector) {
        RailTrackLayer.buildBridgeDeck(world, deckPos, surfaceY, connector);
    }

    private int findRailY(ServerLevel world, int x, int z) {
        return RailTrackLayer.findRailY(world, x, z);
    }
}
