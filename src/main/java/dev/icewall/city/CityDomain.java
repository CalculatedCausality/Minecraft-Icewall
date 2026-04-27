package dev.icewall.city;

import dev.icewall.config.IceWallConfig;
import java.util.Random;
import net.minecraft.server.level.ServerLevel;

/**
 * Shared data types and deterministic identity helpers for survivor cities.
 *
 * All methods are stateless; the enums and records are package-visible so the
 * other city classes can use them without re-declaring them.
 */
public final class CityDomain {

    // -----------------------------------------------------------------------
    // Enums
    // -----------------------------------------------------------------------

    public enum CityStyle {
        TIMBERHAVEN,
        GLASSWARD,
        FROST_FORGE,
        LIBRARY_SPIRE,
        MARKET_RING
    }

    public enum CityDistrict {
        FORGEWARD,
        GARDENWARD,
        ARCHIVEWARD,
        MARKETWARD,
        CITADEL
    }

    public enum BuildingType {
        WATCHTOWER,
        GREENHOUSE,
        FORGE,
        ARCHIVE,
        BUNKHOUSE,
        MARKET,
        CHAPEL,
        WAREHOUSE
    }

    // -----------------------------------------------------------------------
    // Records
    // -----------------------------------------------------------------------

    public record CityProfile(String name, CityStyle style, CityDistrict district) {}

    public record CityAnchor(int chunkX, int chunkZ, long seed) {}

    // -----------------------------------------------------------------------
    // Identity helpers
    // -----------------------------------------------------------------------

    public static long cityKey(CityAnchor anchor) {
        return (((long) anchor.chunkX) << 32) ^ (anchor.chunkZ & 0xffffffffL);
    }

    public static CityProfile profileFor(CityAnchor anchor) {
        CityStyle style = CityStyle.values()[(int) Math.floorMod(anchor.seed, CityStyle.values().length)];
        CityDistrict district = CityDistrict.values()[(int) Math.floorMod(anchor.seed / 17L, CityDistrict.values().length)];
        String name = nameFor(anchor.seed, style, district);
        return new CityProfile(name, style, district);
    }

    public static String nameFor(long seed, CityStyle style, CityDistrict district) {
        String[] prefixes = {"North", "Ash", "Iron", "Frost", "Lantern", "Hearth", "Cinder", "Glass", "Oak", "Bell"};
        String[] suffixes = {"hold", "cross", "mere", "gate", "ward", "spire", "haven", "reach", "market", "watch"};
        String prefix = prefixes[(int) Math.floorMod(seed, prefixes.length)];
        String suffix = suffixes[(int) Math.floorMod(seed / 31L, suffixes.length)];
        if (style == CityStyle.LIBRARY_SPIRE) {
            String title = districtLabel(district);
            return prefix + suffix + " " + title;
        }
        return prefix + suffix;
    }

    public static String styleLabel(CityStyle style) {
        return switch (style) {
            case TIMBERHAVEN   -> "Timberhaven";
            case GLASSWARD     -> "Glassward";
            case FROST_FORGE   -> "Frost Forge";
            case LIBRARY_SPIRE -> "Library Spire";
            case MARKET_RING   -> "Market Ring";
        };
    }

    public static String districtLabel(CityDistrict district) {
        return switch (district) {
            case FORGEWARD   -> "Forgeward";
            case GARDENWARD  -> "Gardenward";
            case ARCHIVEWARD -> "Archiveward";
            case MARKETWARD  -> "Marketward";
            case CITADEL     -> "Citadel";
        };
    }

    // -----------------------------------------------------------------------
    // Anchor lookup (mirrors SurvivorCityGenerator logic exactly)
    // -----------------------------------------------------------------------

    public static CityAnchor nearestAnchor(ServerLevel world, int chunkX, int chunkZ) {
        int spacing = IceWallConfig.CITY_SPACING_CHUNKS;
        int baseX = Math.floorDiv(chunkX, spacing) * spacing;
        int baseZ = Math.floorDiv(chunkZ, spacing) * spacing;
        CityAnchor best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int ox = -spacing; ox <= spacing; ox += spacing) {
            for (int oz = -spacing; oz <= spacing; oz += spacing) {
                int gridX = baseX + ox;
                int gridZ = baseZ + oz;
                long seed = seedFor(world, gridX, gridZ);
                Random rng = new Random(seed);
                int anchorX = gridX + rng.nextInt(spacing / 2 + 1) - spacing / 4;
                int anchorZ = gridZ + rng.nextInt(spacing / 2 + 1) - spacing / 4;
                int dist = Math.abs(chunkX - anchorX) + Math.abs(chunkZ - anchorZ);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = new CityAnchor(anchorX, anchorZ, seed);
                }
            }
        }
        return best;
    }

    public static long seedFor(ServerLevel world, int gridX, int gridZ) {
        return world.getSeed() ^ (gridX * 341873128712L) ^ (gridZ * 132897987541L)
                ^ 0x51A7EED5C17L;
    }

    // -----------------------------------------------------------------------
    // Building selection
    // -----------------------------------------------------------------------

    public static BuildingType chooseBuilding(int dx, int dz, CityDistrict district, Random random) {
        if (random.nextInt(100) < 45) {
            return switch (district) {
                case FORGEWARD   -> random.nextBoolean() ? BuildingType.FORGE : BuildingType.WAREHOUSE;
                case GARDENWARD  -> random.nextBoolean() ? BuildingType.GREENHOUSE : BuildingType.BUNKHOUSE;
                case ARCHIVEWARD -> random.nextBoolean() ? BuildingType.ARCHIVE : BuildingType.CHAPEL;
                case MARKETWARD  -> random.nextBoolean() ? BuildingType.MARKET : BuildingType.WAREHOUSE;
                case CITADEL     -> random.nextBoolean() ? BuildingType.WATCHTOWER : BuildingType.BUNKHOUSE;
            };
        }
        int value = Math.floorMod(dx * 17 + dz * 31 + random.nextInt(8), BuildingType.values().length);
        return BuildingType.values()[value];
    }

    private CityDomain() {}
}
