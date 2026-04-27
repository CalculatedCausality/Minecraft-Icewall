package dev.icewall.city;

import dev.icewall.city.CityDomain.BuildingType;
import dev.icewall.city.CityDomain.CityDistrict;
import dev.icewall.city.CityDomain.CityProfile;
import dev.icewall.city.CityDomain.CityStyle;
import dev.icewall.config.IceWallConfig;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Stateless block-placement helpers for survivor cities.
 *
 * Every method here places blocks in the world; none hold state.
 * The CityLootHelper handles container stocking so this class stays
 * focused on geometry.
 */
public final class CityBlockBuilder {

    // -----------------------------------------------------------------------
    // Surface helpers
    // -----------------------------------------------------------------------

    public static int surfaceY(ServerLevel world, int x, int z) {
        return world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    public static int averageSurfaceY(ServerLevel world, int originX, int originZ, int sizeX, int sizeZ) {
        int total = 0, samples = 0;
        for (int x = originX; x < originX + sizeX; x += Math.max(1, sizeX - 1)) {
            for (int z = originZ; z < originZ + sizeZ; z += Math.max(1, sizeZ - 1)) {
                total += surfaceY(world, x, z);
                samples++;
            }
        }
        return Math.max(world.getMinY() + 8, total / Math.max(1, samples));
    }

    // -----------------------------------------------------------------------
    // Primitive placement
    // -----------------------------------------------------------------------

    public static void clearColumn(ServerLevel world, int x, int z, int minY, int maxY) {
        for (int y = minY; y <= maxY; y++) {
            world.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    public static void pave(ServerLevel world, int x, int z, CityStyle style) {
        int y = surfaceY(world, x, z);
        BlockState pavement = switch (style) {
            case TIMBERHAVEN   -> Blocks.COARSE_DIRT.defaultBlockState();
            case GLASSWARD     -> Blocks.SMOOTH_STONE.defaultBlockState();
            case FROST_FORGE   -> Blocks.POLISHED_DEEPSLATE.defaultBlockState();
            case LIBRARY_SPIRE -> Blocks.STONE_BRICKS.defaultBlockState();
            case MARKET_RING   -> Blocks.TERRACOTTA.defaultBlockState();
        };
        world.setBlock(new BlockPos(x, y - 1, z), pavement, Block.UPDATE_CLIENTS);
        clearColumn(world, x, z, y, y + 3);
    }

    public static void placeLamp(ServerLevel world, int x, int z, CityStyle style) {
        int y = surfaceY(world, x, z);
        world.setBlock(new BlockPos(x, y,     z), Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_CLIENTS);
        world.setBlock(new BlockPos(x, y + 1, z), Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_CLIENTS);
        BlockState light = style == CityStyle.GLASSWARD
                ? Blocks.END_ROD.defaultBlockState()
                : Blocks.LANTERN.defaultBlockState();
        world.setBlock(new BlockPos(x, y + 2, z), light, Block.UPDATE_CLIENTS);
    }

    // -----------------------------------------------------------------------
    // Streets and perimeter
    // -----------------------------------------------------------------------

    public static void buildStreetCross(ServerLevel world, int minX, int minZ, CityStyle style) {
        for (int i = 0; i < 16; i++) {
            pave(world, minX + 7, minZ + i, style);
            pave(world, minX + 8, minZ + i, style);
            pave(world, minX + i, minZ + 7, style);
            pave(world, minX + i, minZ + 8, style);
        }
        placeLamp(world, minX + 5,  minZ + 5,  style);
        placeLamp(world, minX + 10, minZ + 5,  style);
        placeLamp(world, minX + 5,  minZ + 10, style);
        placeLamp(world, minX + 10, minZ + 10, style);
    }

    public static void buildPerimeterWalls(ServerLevel world, int minX, int minZ, int dx, int dz, CityStyle style) {
        int radius = IceWallConfig.CITY_RADIUS_CHUNKS;
        if (Math.abs(dx) != radius && Math.abs(dz) != radius) return;

        BlockState wall = trimBlock(BuildingType.WAREHOUSE, style);
        BlockState cap  = roofBlock(BuildingType.WAREHOUSE, style);

        if (dz == -radius) {
            for (int lx = 0; lx < 16; lx++) buildWallSegment(world, minX + lx, minZ,      wall, cap, dx == 0 && (lx == 7 || lx == 8));
            if (dx == 0) buildGateArch(world, minX + 7, minZ, true, cap);
        }
        if (dz == radius) {
            for (int lx = 0; lx < 16; lx++) buildWallSegment(world, minX + lx, minZ + 15, wall, cap, dx == 0 && (lx == 7 || lx == 8));
            if (dx == 0) buildGateArch(world, minX + 7, minZ + 15, true, cap);
        }
        if (dx == -radius) {
            for (int lz = 0; lz < 16; lz++) buildWallSegment(world, minX,      minZ + lz, wall, cap, dz == 0 && (lz == 7 || lz == 8));
            if (dz == 0) buildGateArch(world, minX, minZ + 7, false, cap);
        }
        if (dx == radius) {
            for (int lz = 0; lz < 16; lz++) buildWallSegment(world, minX + 15, minZ + lz, wall, cap, dz == 0 && (lz == 7 || lz == 8));
            if (dz == 0) buildGateArch(world, minX + 15, minZ + 7, false, cap);
        }
    }

    private static void buildWallSegment(ServerLevel world, int x, int z, BlockState wall, BlockState cap, boolean gate) {
        int y = surfaceY(world, x, z);
        if (gate) { clearColumn(world, x, z, y, y + 4); return; }
        world.setBlock(new BlockPos(x, y - 1, z), Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_CLIENTS);
        for (int h = 0; h < 3; h++) world.setBlock(new BlockPos(x, y + h, z), wall, Block.UPDATE_CLIENTS);
        world.setBlock(new BlockPos(x, y + 3, z), cap, Block.UPDATE_CLIENTS);
    }

    private static void buildGateArch(ServerLevel world, int x, int z, boolean northSouth, BlockState cap) {
        int y = surfaceY(world, x, z);
        for (int i = 0; i < 2; i++) {
            int px = northSouth ? x + i : x;
            int pz = northSouth ? z : z + i;
            world.setBlock(new BlockPos(px, y + 3, pz), cap, Block.UPDATE_CLIENTS);
            world.setBlock(new BlockPos(px, y + 4, pz), Blocks.LANTERN.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    // -----------------------------------------------------------------------
    // Plaza
    // -----------------------------------------------------------------------

    public static void buildCentralPlaza(ServerLevel world, int centerX, int centerZ, CityProfile profile,
            CityLootHelper loot) {
        CityStyle style = profile.style();
        for (int dx = -7; dx <= 7; dx++) {
            for (int dz = -7; dz <= 7; dz++) {
                if (Math.abs(dx) + Math.abs(dz) <= 10) pave(world, centerX + dx, centerZ + dz, style);
            }
        }
        int y = surfaceY(world, centerX, centerZ);
        BlockState monument = switch (style) {
            case TIMBERHAVEN   -> Blocks.OAK_LOG.defaultBlockState();
            case GLASSWARD     -> Blocks.AMETHYST_BLOCK.defaultBlockState();
            case FROST_FORGE   -> Blocks.ANVIL.defaultBlockState();
            case LIBRARY_SPIRE -> Blocks.CHISELED_BOOKSHELF.defaultBlockState();
            case MARKET_RING   -> Blocks.BELL.defaultBlockState();
        };
        for (int h = 0; h < 5; h++) world.setBlock(new BlockPos(centerX, y + h, centerZ), monument, Block.UPDATE_CLIENTS);
        buildDistrictLandmark(world, centerX, y, centerZ, profile, loot);
        placeLamp(world, centerX - 5, centerZ, style);
        placeLamp(world, centerX + 5, centerZ, style);
        placeLamp(world, centerX, centerZ - 5, style);
        placeLamp(world, centerX, centerZ + 5, style);
    }

    private static void buildDistrictLandmark(ServerLevel world, int cx, int y, int cz,
            CityProfile profile, CityLootHelper loot) {
        switch (profile.district()) {
            case FORGEWARD -> {
                world.setBlock(new BlockPos(cx - 2, y, cz), Blocks.BLAST_FURNACE.defaultBlockState(), Block.UPDATE_CLIENTS);
                world.setBlock(new BlockPos(cx + 2, y, cz), Blocks.ANVIL.defaultBlockState(), Block.UPDATE_CLIENTS);
                world.setBlock(new BlockPos(cx, y + 5, cz), Blocks.CAMPFIRE.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            case GARDENWARD -> {
                world.setBlock(new BlockPos(cx - 2, y, cz), Blocks.FLOWERING_AZALEA.defaultBlockState(), Block.UPDATE_CLIENTS);
                world.setBlock(new BlockPos(cx + 2, y, cz), Blocks.HAY_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
                world.setBlock(new BlockPos(cx, y + 5, cz), Blocks.BEE_NEST.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            case ARCHIVEWARD -> {
                world.setBlock(new BlockPos(cx - 2, y, cz), Blocks.LECTERN.defaultBlockState(), Block.UPDATE_CLIENTS);
                world.setBlock(new BlockPos(cx + 2, y, cz), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_CLIENTS);
                world.setBlock(new BlockPos(cx, y + 5, cz), Blocks.LIGHTNING_ROD.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            case MARKETWARD -> {
                world.setBlock(new BlockPos(cx - 2, y, cz), Blocks.BARREL.defaultBlockState(), Block.UPDATE_CLIENTS);
                world.setBlock(new BlockPos(cx + 2, y, cz), Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS);
                world.setBlock(new BlockPos(cx, y + 5, cz), Blocks.BELL.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            case CITADEL -> {
                world.setBlock(new BlockPos(cx - 2, y, cz), Blocks.IRON_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
                world.setBlock(new BlockPos(cx + 2, y, cz), Blocks.IRON_BARS.defaultBlockState(), Block.UPDATE_CLIENTS);
                world.setBlock(new BlockPos(cx, y + 5, cz), Blocks.SEA_LANTERN.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        BlockPos cache = new BlockPos(cx, y, cz - 3);
        world.setBlock(cache, Blocks.BARREL.defaultBlockState(), Block.UPDATE_CLIENTS);
        loot.stockCityCache(world, cache, profile);
        buildBulletinBoard(world, cx + 4, y, cz + 3, profile, loot);
    }

    public static void buildBulletinBoard(ServerLevel world, int x, int y, int z,
            CityProfile profile, CityLootHelper loot) {
        world.setBlock(new BlockPos(x,     y,     z), Blocks.OAK_FENCE.defaultBlockState(),  Block.UPDATE_CLIENTS);
        world.setBlock(new BlockPos(x + 1, y,     z), Blocks.OAK_FENCE.defaultBlockState(),  Block.UPDATE_CLIENTS);
        world.setBlock(new BlockPos(x,     y + 1, z), Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_CLIENTS);
        world.setBlock(new BlockPos(x + 1, y + 1, z), Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_CLIENTS);
        world.setBlock(new BlockPos(x,     y + 2, z), Blocks.LECTERN.defaultBlockState(),     Block.UPDATE_CLIENTS);
        world.setBlock(new BlockPos(x + 1, y + 3, z), Blocks.BELL.defaultBlockState(),        Block.UPDATE_CLIENTS);
        BlockPos cache = new BlockPos(x + 1, y + 2, z);
        world.setBlock(cache, Blocks.BARREL.defaultBlockState(), Block.UPDATE_CLIENTS);
        loot.stockCityCache(world, cache, profile);
    }

    public static void buildPocketPark(ServerLevel world, int cx, int cz, CityStyle style, Random random) {
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                int y = surfaceY(world, cx + dx, cz + dz);
                if (Math.abs(dx) + Math.abs(dz) < 7) {
                    world.setBlock(new BlockPos(cx + dx, y - 1, cz + dz), Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        for (int i = 0; i < 4; i++) {
            int x = cx - 4 + random.nextInt(9);
            int z = cz - 4 + random.nextInt(9);
            int y = surfaceY(world, x, z);
            BlockState plant = style == CityStyle.GLASSWARD
                    ? Blocks.AZALEA.defaultBlockState()
                    : Blocks.SPRUCE_SAPLING.defaultBlockState();
            world.setBlock(new BlockPos(x, y, z), plant, Block.UPDATE_CLIENTS);
        }
    }

    // -----------------------------------------------------------------------
    // Buildings
    // -----------------------------------------------------------------------

    public static void buildBuilding(ServerLevel world, int originX, int originZ, int sizeX, int sizeZ,
            BuildingType type, CityProfile profile, Random random, CityLootHelper loot) {
        CityStyle style = profile.style();
        int baseY  = averageSurfaceY(world, originX, originZ, sizeX, sizeZ);
        BlockState wall  = wallBlock(type, style);
        BlockState trim  = trimBlock(type, style);
        BlockState floor = floorBlock(style);
        BlockState roof  = roofBlock(type, style);
        int height = switch (type) {
            case WATCHTOWER -> 11 + random.nextInt(5);
            case GREENHOUSE -> 5;
            case FORGE      -> 6;
            case ARCHIVE    -> 8;
            case BUNKHOUSE  -> 5 + random.nextInt(2);
            case MARKET     -> 4;
            case CHAPEL     -> 9;
            case WAREHOUSE  -> 7;
        };

        flatten(world, originX - 1, originZ - 1, sizeX + 2, sizeZ + 2, baseY, floor);
        for (int x = originX; x < originX + sizeX; x++) {
            for (int z = originZ; z < originZ + sizeZ; z++) {
                boolean edge = (x == originX || z == originZ || x == originX + sizeX - 1 || z == originZ + sizeZ - 1);
                if (!edge) { clearColumn(world, x, z, baseY, baseY + height + 3); continue; }
                for (int y = baseY; y < baseY + height; y++) {
                    boolean corner = (x == originX || x == originX + sizeX - 1) && (z == originZ || z == originZ + sizeZ - 1);
                    boolean window = y > baseY + 1 && y % 3 == 0 && !corner && ((x + z + y) % 2 == 0);
                    world.setBlock(new BlockPos(x, y, z), corner ? trim : (window ? windowBlock(type, style) : wall), Block.UPDATE_CLIENTS);
                }
            }
        }
        carveDoor(world, originX + sizeX / 2, originZ, baseY);
        buildRoof(world, originX, originZ, sizeX, sizeZ, baseY + height, roof, type);
        addRooftopDetail(world, originX, originZ, sizeX, sizeZ, baseY + height + 2, type, style);
        decorateInterior(world, originX, originZ, sizeX, sizeZ, baseY, type, profile, random, loot);
        loot.spawnResident(world, originX + sizeX / 2, baseY, originZ + sizeZ / 2, type, profile, random);
    }

    private static void flatten(ServerLevel world, int originX, int originZ, int sizeX, int sizeZ,
            int baseY, BlockState floor) {
        for (int x = originX; x < originX + sizeX; x++) {
            for (int z = originZ; z < originZ + sizeZ; z++) {
                for (int y = baseY - 1; y >= Math.max(world.getMinY(), baseY - 6); y--) {
                    if (world.getBlockState(new BlockPos(x, y, z)).isSolid()) break;
                    world.setBlock(new BlockPos(x, y, z), Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
                world.setBlock(new BlockPos(x, baseY - 1, z), floor, Block.UPDATE_CLIENTS);
                clearColumn(world, x, z, baseY, baseY + 16);
            }
        }
    }

    private static void buildRoof(ServerLevel world, int originX, int originZ, int sizeX, int sizeZ,
            int roofY, BlockState roof, BuildingType type) {
        if (type == BuildingType.WATCHTOWER || type == BuildingType.CHAPEL) {
            int cx = originX + sizeX / 2, cz = originZ + sizeZ / 2;
            for (int r = Math.max(sizeX, sizeZ) / 2; r >= 0; r--) {
                int y = roofY + (Math.max(sizeX, sizeZ) / 2 - r);
                for (int x = cx - r; x <= cx + r; x++)
                    for (int z = cz - r; z <= cz + r; z++)
                        if (Math.abs(x - cx) + Math.abs(z - cz) <= r + 1)
                            world.setBlock(new BlockPos(x, y, z), roof, Block.UPDATE_CLIENTS);
            }
            return;
        }
        for (int x = originX - 1; x <= originX + sizeX; x++)
            for (int z = originZ - 1; z <= originZ + sizeZ; z++) {
                boolean edge = x == originX - 1 || x == originX + sizeX || z == originZ - 1 || z == originZ + sizeZ;
                world.setBlock(new BlockPos(x, roofY + (edge ? 0 : 1), z), roof, Block.UPDATE_CLIENTS);
            }
    }

    private static void addRooftopDetail(ServerLevel world, int originX, int originZ, int sizeX, int sizeZ,
            int roofY, BuildingType type, CityStyle style) {
        int cx = originX + sizeX / 2, cz = originZ + sizeZ / 2;
        switch (type) {
            case WATCHTOWER -> {
                for (int h = 0; h < 4; h++) world.setBlock(new BlockPos(cx, roofY + h, cz), Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_CLIENTS);
                world.setBlock(new BlockPos(cx, roofY + 4, cz), Blocks.RED_BANNER.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            case GREENHOUSE -> {
                world.setBlock(new BlockPos(cx,     roofY, cz), Blocks.BEE_NEST.defaultBlockState(),        Block.UPDATE_CLIENTS);
                world.setBlock(new BlockPos(cx + 1, roofY, cz), Blocks.FLOWERING_AZALEA.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            case FORGE -> {
                for (int h = 0; h < 4; h++) world.setBlock(new BlockPos(cx, roofY + h, cz), Blocks.BRICKS.defaultBlockState(), Block.UPDATE_CLIENTS);
                world.setBlock(new BlockPos(cx, roofY + 4, cz), Blocks.CAMPFIRE.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            case ARCHIVE  -> world.setBlock(new BlockPos(cx, roofY, cz), Blocks.LIGHTNING_ROD.defaultBlockState(), Block.UPDATE_CLIENTS);
            case BUNKHOUSE -> {
                world.setBlock(new BlockPos(cx - 1, roofY, cz), Blocks.BARREL.defaultBlockState(), Block.UPDATE_CLIENTS);
                world.setBlock(new BlockPos(cx + 1, roofY, cz), Blocks.CHEST.defaultBlockState(),  Block.UPDATE_CLIENTS);
            }
            case MARKET -> {
                world.setBlock(new BlockPos(cx - 1, roofY, cz), Blocks.YELLOW_WOOL.defaultBlockState(), Block.UPDATE_CLIENTS);
                world.setBlock(new BlockPos(cx,     roofY, cz), Blocks.RED_WOOL.defaultBlockState(),    Block.UPDATE_CLIENTS);
                world.setBlock(new BlockPos(cx + 1, roofY, cz), Blocks.BLUE_WOOL.defaultBlockState(),   Block.UPDATE_CLIENTS);
            }
            case CHAPEL -> {
                for (int h = 0; h < 3; h++) world.setBlock(new BlockPos(cx, roofY + h, cz), Blocks.BLUE_ICE.defaultBlockState(), Block.UPDATE_CLIENTS);
                world.setBlock(new BlockPos(cx, roofY + 3, cz), Blocks.SEA_LANTERN.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            case WAREHOUSE -> world.setBlock(new BlockPos(cx, roofY, cz), Blocks.IRON_CHAIN.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        if (style == CityStyle.GLASSWARD)
            world.setBlock(new BlockPos(cx, roofY + 1, cz + 1), Blocks.END_ROD.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    private static void decorateInterior(ServerLevel world, int originX, int originZ, int sizeX, int sizeZ,
            int baseY, BuildingType type, CityProfile profile, Random random, CityLootHelper loot) {
        CityStyle style = profile.style();
        int cx = originX + sizeX / 2, cz = originZ + sizeZ / 2;
        switch (type) {
            case WATCHTOWER -> {
                for (int y = baseY; y < baseY + 9; y += 2)
                    world.setBlock(new BlockPos(cx, y, cz), Blocks.LADDER.defaultBlockState(), Block.UPDATE_CLIENTS);
                world.setBlock(new BlockPos(cx, baseY + 9, cz), Blocks.BELL.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            case GREENHOUSE -> {
                for (int x = originX + 2; x < originX + sizeX - 2; x++) {
                    world.setBlock(new BlockPos(x, baseY,     cz), Blocks.FARMLAND.defaultBlockState(), Block.UPDATE_CLIENTS);
                    world.setBlock(new BlockPos(x, baseY + 1, cz), Blocks.WHEAT.defaultBlockState(),    Block.UPDATE_CLIENTS);
                }
            }
            case FORGE -> {
                world.setBlock(new BlockPos(cx,     baseY, cz), Blocks.LAVA.defaultBlockState(),          Block.UPDATE_CLIENTS);
                world.setBlock(new BlockPos(cx + 1, baseY, cz), Blocks.ANVIL.defaultBlockState(),         Block.UPDATE_CLIENTS);
                world.setBlock(new BlockPos(cx - 1, baseY, cz), Blocks.BLAST_FURNACE.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            case ARCHIVE -> {
                for (int z = originZ + 1; z < originZ + sizeZ - 1; z++) {
                    world.setBlock(new BlockPos(originX + 1,          baseY, z), Blocks.BOOKSHELF.defaultBlockState(),         Block.UPDATE_CLIENTS);
                    world.setBlock(new BlockPos(originX + sizeX - 2,  baseY, z), Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
                world.setBlock(new BlockPos(cx, baseY, cz), Blocks.LECTERN.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            case BUNKHOUSE -> {
                for (int i = 0; i < 3; i++)
                    world.setBlock(new BlockPos(originX + 2 + i * 2, baseY, originZ + 2), Blocks.RED_BED.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            case MARKET -> {
                for (int i = 0; i < 3; i++) {
                    BlockPos chest = new BlockPos(originX + 2 + i * 2, baseY, cz);
                    world.setBlock(chest, Blocks.BARREL.defaultBlockState(), Block.UPDATE_CLIENTS);
                    loot.stockContainer(world, chest, type, profile.district(), random);
                }
            }
            case CHAPEL -> {
                world.setBlock(new BlockPos(cx, baseY,     cz), Blocks.CAMPFIRE.defaultBlockState(), Block.UPDATE_CLIENTS);
                world.setBlock(new BlockPos(cx, baseY + 1, cz), Blocks.BLUE_ICE.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            case WAREHOUSE -> {
                for (int i = 0; i < 4; i++) {
                    BlockPos barrel = new BlockPos(originX + 2 + i, baseY, originZ + sizeZ - 2);
                    world.setBlock(barrel, Blocks.BARREL.defaultBlockState(), Block.UPDATE_CLIENTS);
                    loot.stockContainer(world, barrel, type, profile.district(), random);
                }
            }
        }
        placeLamp(world, originX + 1,          originZ + 1,          style);
        placeLamp(world, originX + sizeX - 2,  originZ + sizeZ - 2,  style);
    }

    private static void carveDoor(ServerLevel world, int x, int z, int baseY) {
        world.setBlock(new BlockPos(x, baseY,     z), Blocks.AIR.defaultBlockState(),      Block.UPDATE_CLIENTS);
        world.setBlock(new BlockPos(x, baseY + 1, z), Blocks.AIR.defaultBlockState(),      Block.UPDATE_CLIENTS);
        world.setBlock(new BlockPos(x, baseY - 1, z), Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    // -----------------------------------------------------------------------
    // Ruin conversion
    // -----------------------------------------------------------------------

    public static void lightSignalFires(ServerLevel world, int centerX, int centerZ, int radiusBlocks) {
        for (int dx = -radiusBlocks; dx <= radiusBlocks; dx += 8) {
            for (int dz = -radiusBlocks; dz <= radiusBlocks; dz += 8) {
                int x = centerX + dx, z = centerZ + dz;
                int topY = surfaceY(world, x, z);
                for (int y = topY; y <= topY + 22; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (state.is(Blocks.CAMPFIRE) && !state.getValue(CampfireBlock.LIT)) {
                        world.setBlock(pos, state.setValue(CampfireBlock.LIT, true), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    public static BlockState frozenStateFor(BlockState state, CityDistrict district, Random random) {
        if (state.isAir()) return null;
        if (state.is(Blocks.OAK_PLANKS) || state.is(Blocks.SPRUCE_PLANKS) || state.is(Blocks.DARK_OAK_PLANKS)
                || state.is(Blocks.STRIPPED_OAK_WOOD) || state.is(Blocks.OAK_LOG)
                || state.is(Blocks.DARK_OAK_LOG)      || state.is(Blocks.OAK_FENCE)) {
            return random.nextInt(3) == 0 ? Blocks.PACKED_ICE.defaultBlockState() : Blocks.SPRUCE_PLANKS.defaultBlockState();
        }
        if (state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE) || state.is(Blocks.LIGHT_BLUE_STAINED_GLASS)) {
            return random.nextBoolean() ? Blocks.BLUE_ICE.defaultBlockState() : Blocks.PACKED_ICE.defaultBlockState();
        }
        if (state.is(Blocks.BOOKSHELF) || state.is(Blocks.CHISELED_BOOKSHELF)) {
            return district == CityDistrict.ARCHIVEWARD && random.nextInt(4) == 0
                    ? Blocks.CHEST.defaultBlockState() : Blocks.PACKED_ICE.defaultBlockState();
        }
        if (state.is(Blocks.LANTERN) || state.is(Blocks.END_ROD) || state.is(Blocks.TORCH) || state.is(Blocks.CAMPFIRE)) {
            return random.nextBoolean() ? Blocks.BLUE_ICE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }
        if (state.is(Blocks.WHEAT) || state.is(Blocks.FLOWERING_AZALEA) || state.is(Blocks.AZALEA) || state.is(Blocks.SPRUCE_SAPLING)) {
            return Blocks.DEAD_BUSH.defaultBlockState();
        }
        if (random.nextInt(90) == 0 && state.isSolid()) return Blocks.BLUE_ICE.defaultBlockState();
        return null;
    }

    // -----------------------------------------------------------------------
    // Material selectors
    // -----------------------------------------------------------------------

    public static BlockState wallBlock(BuildingType type, CityStyle style) {
        return switch (style) {
            case TIMBERHAVEN   -> type == BuildingType.GREENHOUSE ? Blocks.GLASS.defaultBlockState() : Blocks.STRIPPED_OAK_WOOD.defaultBlockState();
            case GLASSWARD     -> (type == BuildingType.GREENHOUSE || type == BuildingType.ARCHIVE) ? Blocks.GLASS.defaultBlockState() : Blocks.QUARTZ_BRICKS.defaultBlockState();
            case FROST_FORGE   -> Blocks.DEEPSLATE_BRICKS.defaultBlockState();
            case LIBRARY_SPIRE -> Blocks.BOOKSHELF.defaultBlockState();
            case MARKET_RING   -> Blocks.CUT_COPPER.defaultBlockState();
        };
    }

    public static BlockState trimBlock(BuildingType type, CityStyle style) {
        return switch (style) {
            case TIMBERHAVEN   -> Blocks.DARK_OAK_LOG.defaultBlockState();
            case GLASSWARD     -> Blocks.AMETHYST_BLOCK.defaultBlockState();
            case FROST_FORGE   -> Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
            case LIBRARY_SPIRE -> Blocks.OAK_LOG.defaultBlockState();
            case MARKET_RING   -> Blocks.SMOOTH_STONE.defaultBlockState();
        };
    }

    public static BlockState floorBlock(CityStyle style) {
        return switch (style) {
            case TIMBERHAVEN   -> Blocks.OAK_PLANKS.defaultBlockState();
            case GLASSWARD     -> Blocks.SMOOTH_QUARTZ.defaultBlockState();
            case FROST_FORGE   -> Blocks.POLISHED_DEEPSLATE.defaultBlockState();
            case LIBRARY_SPIRE -> Blocks.SPRUCE_PLANKS.defaultBlockState();
            case MARKET_RING   -> Blocks.TERRACOTTA.defaultBlockState();
        };
    }

    public static BlockState roofBlock(BuildingType type, CityStyle style) {
        if (type == BuildingType.GREENHOUSE) return Blocks.GLASS.defaultBlockState();
        return switch (style) {
            case TIMBERHAVEN   -> Blocks.SPRUCE_PLANKS.defaultBlockState();
            case GLASSWARD     -> Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState();
            case FROST_FORGE   -> Blocks.DEEPSLATE_TILES.defaultBlockState();
            case LIBRARY_SPIRE -> Blocks.DARK_OAK_PLANKS.defaultBlockState();
            case MARKET_RING   -> Blocks.COPPER_BLOCK.defaultBlockState();
        };
    }

    public static BlockState windowBlock(BuildingType type, CityStyle style) {
        if (type == BuildingType.FORGE) return Blocks.IRON_BARS.defaultBlockState();
        return style == CityStyle.GLASSWARD
                ? Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState()
                : Blocks.GLASS_PANE.defaultBlockState();
    }

    private CityBlockBuilder() {}
}
