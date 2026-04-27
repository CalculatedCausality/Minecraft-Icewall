package dev.icewall.wall;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Stateless settlement build-plan generator for {@link VillagerCommunities}.
 *
 * Call {@link #generatePlan(ServerLevel, int, int, int)} to get an ordered list
 * of {@link PlacedBlock} instructions; place them one per tick for animated
 * construction.
 */
public final class CommunityBuilder {

    /** A single block placement: position + desired state. */
    public record PlacedBlock(BlockPos pos, BlockState state) {}

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    /**
     * Generates a fully procedural settlement build plan seeded on position.
     * The same community coordinates always produce the same plan.
     */
    public static List<PlacedBlock> generatePlan(ServerLevel world, int cx, int cz, int villagers) {
        List<PlacedBlock> plan = new ArrayList<>();
        long seed = (long) cx * 341873128712L ^ (long) cz * 132897987541L;
        Random br = new Random(seed);

        BuildingTheme theme = THEMES[br.nextInt(THEMES.length)];
        int numBuildings = Math.min(4, Math.max(1, (villagers + 1) / 2));

        addLandmark(plan, world, cx, cz, theme, br);

        int thY = world.getHeight(Heightmap.Types.WORLD_SURFACE, cx, cz - 22);
        addBuilding(plan, cx, thY, cz - 22, theme, Archetype.TOWN_HALL, 3, 4, br);

        int[][] offsets = buildOffsets(numBuildings, br);
        addRoads(plan, world, cx, cz, offsets, theme);

        int numGardens = Math.max(1, villagers / 3);
        int[][] gardenOffsets = gardenOffsets(numGardens, offsets, br);
        for (int[] go : gardenOffsets) {
            int gx = cx + go[0], gz = cz + go[1];
            int gy = world.getHeight(Heightmap.Types.WORLD_SURFACE, gx, gz);
            addGarden(plan, gx, gy, gz, br);
        }

        for (int[] off : offsets) {
            int bx = cx + off[0], bz = cz + off[1];
            int baseY = world.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
            Archetype arch = ARCHETYPES[br.nextInt(ARCHETYPES.length)];
            int half  = 2 + br.nextInt(2);
            int wallH = (arch == Archetype.WATCHTOWER) ? 5 : (2 + br.nextInt(2));
            addBuilding(plan, bx, baseY, bz, theme, arch, half, wallH, br);
        }
        return plan;
    }

    // -----------------------------------------------------------------------
    // Layout helpers
    // -----------------------------------------------------------------------

    private static int[][] buildOffsets(int count, Random r) {
        int[][] slots = {{0, -18}, {-16, 8}, {16, 8}, {-16, -8}, {14, -16}, {0, 20}};
        int[][] result = new int[count][];
        for (int i = 0; i < count; i++) {
            int[] s = slots[i % slots.length];
            result[i] = new int[]{s[0] + r.nextInt(7) - 3, s[1] + r.nextInt(7) - 3};
        }
        return result;
    }

    private static int[][] gardenOffsets(int count, int[][] buildingOffsets, Random r) {
        int[][] result = new int[count][];
        for (int i = 0; i < count; i++) {
            int[] base = buildingOffsets[i % buildingOffsets.length];
            int xShift = (i % 2 == 0) ? 12 : -12;
            result[i] = new int[]{base[0] + xShift + r.nextInt(5) - 2, base[1] + r.nextInt(5) - 2};
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Block generators
    // -----------------------------------------------------------------------

    private static void addLandmark(List<PlacedBlock> plan, ServerLevel world,
            int cx, int cz, BuildingTheme theme, Random r) {
        int y = world.getHeight(Heightmap.Types.WORLD_SURFACE, cx, cz);
        int type = r.nextInt(3);
        if (type == 0) {
            plan.add(block(cx, y,     cz, theme.foundationBlock));
            plan.add(block(cx, y + 1, cz, theme.fenceBlock));
            plan.add(block(cx, y + 2, cz, theme.fenceBlock));
            plan.add(block(cx, y + 3, cz, Blocks.LANTERN));
        } else if (type == 1) {
            plan.add(block(cx, y,     cz, theme.foundationBlock));
            plan.add(block(cx, y + 1, cz, theme.foundationBlock));
            plan.add(block(cx, y + 2, cz, Blocks.BELL));
        } else {
            plan.add(block(cx, y,     cz, theme.foundationBlock));
            plan.add(block(cx, y + 1, cz, Blocks.CAMPFIRE));
        }
    }

    private static void addBuilding(List<PlacedBlock> plan, int cx, int baseY, int cz,
            BuildingTheme theme, Archetype arch, int half, int wallH, Random r) {
        for (int dx = -half; dx <= half; dx++)
            for (int dz = -half; dz <= half; dz++)
                plan.add(block(cx + dx, baseY, cz + dz, theme.foundationBlock));
        for (int dx = -half; dx <= half; dx++)
            for (int dz = -half; dz <= half; dz++)
                plan.add(block(cx + dx, baseY + 1, cz + dz, theme.floorBlock));

        int entryDz = (arch == Archetype.WATCHTOWER) ? -half : half;
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                if (Math.abs(dx) != half && Math.abs(dz) != half) continue;
                boolean isDoor = (dx == 0 && dz == entryDz);
                for (int dy = 2; dy <= 1 + wallH; dy++) {
                    Block b = (isDoor && (dy == 2 || dy == 3)) ? Blocks.AIR : theme.wallBlock;
                    plan.add(block(cx + dx, baseY + dy, cz + dz, b));
                }
            }
        }
        addRoof(plan, cx, baseY, cz, theme, arch, half, wallH, r);
        addInterior(plan, cx, baseY, cz, arch, half, r);
    }

    private static void addRoof(List<PlacedBlock> plan, int cx, int baseY, int cz,
            BuildingTheme theme, Archetype arch, int half, int wallH, Random r) {
        int roofY = baseY + wallH + 2;
        if (arch == Archetype.WATCHTOWER) {
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    if (Math.abs(dx) != half && Math.abs(dz) != half) continue;
                    plan.add(block(cx + dx, roofY, cz + dz, theme.fenceBlock));
                    if (Math.abs(dx) == half && Math.abs(dz) == half)
                        plan.add(block(cx + dx, roofY + 1, cz + dz, Blocks.LANTERN));
                }
            }
            return;
        }
        int style = r.nextInt(3);
        if (style == 0) {
            for (int dx = -half; dx <= half; dx++)
                for (int dz = -half; dz <= half; dz++)
                    plan.add(block(cx + dx, roofY, cz + dz, theme.roofBlock));
        } else if (style == 1) {
            for (int dx = -half; dx <= half; dx++)
                for (int dz = -half; dz <= half; dz++)
                    plan.add(block(cx + dx, roofY, cz + dz, theme.roofBlock));
            for (int dx = -half; dx <= half; dx++)
                plan.add(block(cx + dx, roofY + 1, cz, theme.roofBlock));
        } else {
            for (int tier = 0; tier <= half; tier++) {
                int r2 = half - tier;
                for (int dx = -r2; dx <= r2; dx++)
                    for (int dz = -r2; dz <= r2; dz++)
                        plan.add(block(cx + dx, roofY + tier, cz + dz, theme.roofBlock));
            }
        }
    }

    private static void addInterior(List<PlacedBlock> plan, int cx, int baseY, int cz,
            Archetype arch, int half, Random r) {
        int fy = baseY + 2, ih = half - 1;
        switch (arch) {
            case DWELLING -> {
                plan.add(block(cx - 1, fy, cz - ih, Blocks.CRAFTING_TABLE));
                plan.add(block(cx,     fy, cz - ih, Blocks.LANTERN));
                plan.add(block(cx + 1, fy, cz - ih, Blocks.FLOWER_POT));
                plan.add(block(cx - 1, fy, cz,      randomCarpet(r)));
                plan.add(block(cx,     fy, cz,      randomCarpet(r)));
                plan.add(block(cx + 1, fy, cz,      randomCarpet(r)));
            }
            case WORKSHOP -> {
                plan.add(block(cx - 1,  fy, cz - ih, Blocks.BLAST_FURNACE));
                plan.add(block(cx,      fy, cz - ih, Blocks.SMITHING_TABLE));
                plan.add(block(cx + 1,  fy, cz - ih, Blocks.ANVIL));
                plan.add(block(cx,      fy, cz,      Blocks.LANTERN));
                plan.add(block(cx - ih, fy, cz + 1,  Blocks.BARREL));
                plan.add(block(cx + ih, fy, cz + 1,  Blocks.BARREL));
            }
            case LIBRARY -> {
                for (int dx = -ih; dx <= ih; dx++) {
                    plan.add(block(cx + dx, fy,     cz - ih, Blocks.BOOKSHELF));
                    plan.add(block(cx + dx, fy + 1, cz - ih, Blocks.BOOKSHELF));
                }
                plan.add(block(cx,     fy, cz, Blocks.LECTERN));
                plan.add(block(cx + 1, fy, cz, Blocks.LANTERN));
            }
            case STOREHOUSE -> {
                plan.add(block(cx - ih, fy, cz - ih, Blocks.BARREL));
                plan.add(block(cx + ih, fy, cz - ih, Blocks.BARREL));
                plan.add(block(cx - ih, fy, cz + ih, Blocks.BARREL));
                plan.add(block(cx + ih, fy, cz + ih, Blocks.BARREL));
                plan.add(block(cx,      fy, cz,      Blocks.CHEST));
                plan.add(block(cx,      fy, cz - 1,  Blocks.LANTERN));
            }
            case WATCHTOWER -> {
                for (int floor = 0; floor < 5; floor++)
                    plan.add(block(cx, baseY + 2 + floor, cz - ih, Blocks.LANTERN));
            }
            case TOWN_HALL -> {
                for (int dz = -ih; dz <= ih; dz++)
                    plan.add(block(cx, fy, cz + dz, randomCarpet(r)));
                for (int dz2 = -ih; dz2 <= ih; dz2++) {
                    plan.add(block(cx - ih, fy,     cz + dz2, Blocks.BOOKSHELF));
                    plan.add(block(cx + ih, fy,     cz + dz2, Blocks.BOOKSHELF));
                    plan.add(block(cx - ih, fy + 1, cz + dz2, Blocks.BOOKSHELF));
                    plan.add(block(cx + ih, fy + 1, cz + dz2, Blocks.BOOKSHELF));
                }
                plan.add(block(cx,     fy, cz - ih, Blocks.LECTERN));
                plan.add(block(cx - 1, fy, cz - ih, Blocks.LANTERN));
                plan.add(block(cx + 1, fy, cz - ih, Blocks.LANTERN));
                plan.add(block(cx,     fy, cz,      Blocks.NOTE_BLOCK));
                plan.add(block(cx,     fy, cz + 1,  Blocks.BELL));
            }
            case GARDEN -> {} // handled by addGarden
        }
    }

    private static void addRoads(List<PlacedBlock> plan, ServerLevel world,
            int cx, int cz, int[][] offsets, BuildingTheme theme) {
        for (int[] off : offsets) {
            int tx = cx + off[0], tz = cz + off[1];
            int step = (tx >= cx) ? 1 : -1;
            for (int x = cx; x != tx; x += step) {
                int y = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, cz);
                plan.add(block(x, y, cz,     Blocks.GRAVEL));
                plan.add(block(x, y, cz + 1, Blocks.GRAVEL));
                if (Math.abs(x - cx) % 8 == 0) plan.add(block(x, y + 1, cz - 1, Blocks.LANTERN));
            }
            step = (tz >= cz) ? 1 : -1;
            for (int z = cz; z != tz; z += step) {
                int y = world.getHeight(Heightmap.Types.WORLD_SURFACE, tx, z);
                plan.add(block(tx,     y, z, Blocks.GRAVEL));
                plan.add(block(tx + 1, y, z, Blocks.GRAVEL));
                if (Math.abs(z - cz) % 8 == 0) plan.add(block(tx - 1, y + 1, z, Blocks.LANTERN));
            }
        }
    }

    private static void addGarden(List<PlacedBlock> plan, int cx, int baseY, int cz, Random r) {
        int half = 3;
        for (int dx = -half; dx <= half; dx++)
            for (int dz = -half; dz <= half; dz++)
                plan.add(block(cx + dx, baseY, cz + dz, Blocks.DIRT));
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                plan.add(block(cx + dx, baseY + 1, cz + dz, Blocks.FARMLAND));
        Block[] crops = {Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES};
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                plan.add(block(cx + dx, baseY + 2, cz + dz, crops[r.nextInt(crops.length)]));
        Block[] flowers = {Blocks.DANDELION, Blocks.POPPY, Blocks.CORNFLOWER, Blocks.TORCHFLOWER};
        for (int dx = -half + 1; dx <= half - 1; dx++) {
            for (int dz = -half + 1; dz <= half - 1; dz++) {
                if (Math.abs(dx) != half - 1 && Math.abs(dz) != half - 1) continue;
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) continue;
                plan.add(block(cx + dx, baseY + 1, cz + dz, Blocks.GRASS_BLOCK));
                plan.add(block(cx + dx, baseY + 2, cz + dz, flowers[r.nextInt(flowers.length)]));
            }
        }
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                if (Math.abs(dx) != half && Math.abs(dz) != half) continue;
                boolean isGate = (dx == 0 && dz == half);
                plan.add(block(cx + dx, baseY + 1, cz + dz, isGate ? Blocks.AIR : Blocks.OAK_FENCE));
            }
        }
        plan.add(block(cx - 2, baseY + 1, cz, Blocks.COMPOSTER));
        plan.add(block(cx + 2, baseY + 1, cz, Blocks.WATER_CAULDRON));
    }

    private static PlacedBlock block(int x, int y, int z, Block b) {
        return new PlacedBlock(new BlockPos(x, y, z), b.defaultBlockState());
    }

    // -----------------------------------------------------------------------
    // Palette
    // -----------------------------------------------------------------------

    private enum Archetype { DWELLING, WORKSHOP, LIBRARY, STOREHOUSE, WATCHTOWER, TOWN_HALL, GARDEN }

    private static final Archetype[] ARCHETYPES = {
        Archetype.DWELLING, Archetype.WORKSHOP, Archetype.LIBRARY,
        Archetype.STOREHOUSE, Archetype.WATCHTOWER
    };

    private static final class BuildingTheme {
        final Block wallBlock, floorBlock, roofBlock, foundationBlock, fenceBlock;
        BuildingTheme(Block w, Block fl, Block ro, Block fo, Block fe) {
            wallBlock = w; floorBlock = fl; roofBlock = ro; foundationBlock = fo; fenceBlock = fe;
        }
    }

    private static final BuildingTheme[] THEMES = {
        new BuildingTheme(Blocks.OAK_LOG,      Blocks.OAK_PLANKS,    Blocks.OAK_PLANKS,    Blocks.COBBLESTONE, Blocks.OAK_FENCE),
        new BuildingTheme(Blocks.SPRUCE_LOG,   Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.STONE,       Blocks.SPRUCE_FENCE),
        new BuildingTheme(Blocks.BIRCH_LOG,    Blocks.BIRCH_PLANKS,  Blocks.BIRCH_PLANKS,  Blocks.COBBLESTONE, Blocks.BIRCH_FENCE),
        new BuildingTheme(Blocks.ACACIA_LOG,   Blocks.ACACIA_PLANKS, Blocks.ACACIA_PLANKS, Blocks.SANDSTONE,   Blocks.ACACIA_FENCE),
        new BuildingTheme(Blocks.STONE_BRICKS, Blocks.OAK_PLANKS,   Blocks.STONE_BRICKS,  Blocks.COBBLESTONE, Blocks.OAK_FENCE),
    };

    private static final Block[] CARPETS = {
        Blocks.RED_CARPET,    Blocks.BLUE_CARPET,   Blocks.GREEN_CARPET,
        Blocks.YELLOW_CARPET, Blocks.ORANGE_CARPET, Blocks.PURPLE_CARPET,
        Blocks.CYAN_CARPET,   Blocks.WHITE_CARPET,
    };

    private static Block randomCarpet(Random r) { return CARPETS[r.nextInt(CARPETS.length)]; }

    private CommunityBuilder() {}
}
