package dev.icewall.config;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class IceWallConfig {
    public static final int DEFAULT_ADVANCE_INTERVAL_TICKS = 20 * 30;
    public static final int MAX_BLOCKS_PER_TICK = 512;
    public static final int START_OFFSET_BLOCKS = 128;
    public static final int BOSS_BAR_DISTANCE = 200;
    public static final boolean REPLACE_SOLIDS = false;
    public static final BlockState WALL_BLOCK = Blocks.PACKED_ICE.defaultBlockState();
    public static final int CHUNK_PRELOAD_AHEAD = 4;

    /**
     * Maximum number of blocks the leading edge of a column can be ahead of wallFrontZ.
     * Each X column gets a deterministic offset in [0, LEAD_VARIATION] so the glacier
     * face looks organically uneven rather than a flat advancing slab.
     */
    public static final int LEAD_VARIATION = 8;

    // Warning distances (blocks ahead of wall) and their matching threshold index used in IceWallAdvancer.
    public static final int[] WARNING_DISTANCES = {200, 100, 50, 10};

    // Environmental corruption — terrain corruption band and sampling budget.
    // CORRUPTION_RANGE: how far ahead of wallFrontZ the freeze/snow effects are applied.
    public static final int CORRUPTION_RANGE = 128;
    // Random positions sampled across the terrain band each tick.
    public static final int CORRUPTION_TERRAIN_SAMPLES = 48;
    // Random positions sampled around each player close to the wall each tick.
    public static final int CORRUPTION_PLAYER_SAMPLES = 16;
    // Block radius around each player to sample for player-proximity corruption.
    public static final int CORRUPTION_PLAYER_RADIUS = 20;
    // Blocks ahead of wallFrontZ within which vegetation is frost-killed (most aggressive zone).
    public static final int CORRUPTION_KILL_VEGETATION_DISTANCE = 32;

    // Cave corruption — underground-specific effects.
    // CAVE_TERRAIN_SAMPLES: positions sampled underground in the corruption band each tick.
    public static final int CAVE_TERRAIN_SAMPLES = 32;
    // Positions sampled around each underground player each tick.
    public static final int CAVE_PLAYER_SAMPLES = 12;
    // Block radius around each underground player for proximity samples.
    public static final int CAVE_PLAYER_RADIUS = 16;
    // Blocks ahead of wall within which ceiling collapse (stone→gravel) is triggered.
    public static final int CAVE_COLLAPSE_DISTANCE = 24;
    // Max distanceAhead at which underground players hear the cave rumble.
    public static final int CAVE_SOUND_DISTANCE = 100;
    // Ticks between per-player cave rumble sound events.
    public static final int CAVE_SOUND_INTERVAL = 40;
    // Y level at or below which stone variants are converted to packed ice (permafrost).
    public static final int PERMAFROST_MAX_Y = 0;
    // Blocks ahead of wall within which stalactite spears may form on cave ceilings.
    public static final int STALACTITE_DISTANCE = 30;

    // Hypothermia — max health reduction via AttributeModifier.
    // Players within this many blocks of the wall accumulate cold levels.
    public static final int HYPOTHERMIA_MAX_DISTANCE = 64;

    // Compass scramble — disorientation actionbar message.
    public static final int COMPASS_SCRAMBLE_DISTANCE = 80;
    public static final int COMPASS_SCRAMBLE_INTERVAL_TICKS = 80;

    // Inventory frost — temporarily renames a hotbar item.
    public static final int INVENTORY_FROST_DISTANCE = 50;
    public static final int INVENTORY_FROST_INTERVAL_TICKS = 200;
    public static final int INVENTORY_FROST_DURATION_TICKS = 60;

    // Ice toll — freeze damage when mining glacier blocks.
    public static final float ICE_TOLL_DAMAGE = 0.5F;
    public static final double ICE_TOLL_CHANCE = 0.15;

    // Survivor supply drop — periodic item drop for the furthest-ahead player.
    public static final int SUPPLY_DROP_INTERVAL_TICKS = 20 * 60 * 10; // 10 min

    // Spectator drift cam — blocks per tick the camera drifts east along the wall face.
    public static final double SPECTATOR_DRIFT_SPEED = 0.025;

    // Surface features (fissures, ice spikes, treasure encasing).
    public static final int FISSURE_DISTANCE_MIN = 10;
    public static final int FISSURE_DISTANCE_MAX = 20;
    public static final int ICE_SPIKE_MIN_DIST = 8;
    public static final int ICE_SPIKE_MAX_DIST = 40;
    public static final int TREASURE_ENCASE_DISTANCE = 64;

    // Campfire safe zones — campfire within this radius slows surface corruption by 70%.
    public static final int CAMPFIRE_SLOW_RADIUS = 8;
    public static final int CAMPFIRE_SCAN_INTERVAL_TICKS = 100;

    // Heatmap — rolling window for per-chunk sample counts.
    public static final int HEATMAP_WINDOW_TICKS = 1200;

    // Weather effects.
    // Distance at which the wall forces thunderstorm weather.
    public static final int BLIZZARD_LOCK_DISTANCE = 300;
    // Distance at which whiteout blindness pulses are applied.
    public static final int WHITEOUT_DISTANCE = 50;
    // Ticks between whiteout blindness pulses.
    public static final int WHITEOUT_INTERVAL_TICKS = 60;
    // Blindness duration per pulse (ticks).
    public static final int WHITEOUT_DURATION_TICKS = 40;
    // Distance within which random lightning strikes are summoned.
    public static final int LIGHTNING_DISTANCE = 80;
    // Average ticks between individual lightning strikes (geometric distribution).
    public static final int LIGHTNING_INTERVAL_TICKS = 600;
    // Freeze-wind knockback force applied to players within this distance.
    public static final int WIND_PUSH_DISTANCE = 20;
    // Distance from which the temperature HUD is shown (actionbar).
    public static final int TEMPERATURE_HUD_DISTANCE = 200;
    // Interval in ticks between temperature HUD updates.
    public static final int TEMPERATURE_HUD_INTERVAL_TICKS = 20;

    // Mob effects.
    // Distance within which mobs are converted/frozen.
    public static final int MOB_CONVERT_DISTANCE = 16;
    // Distance within which trees snap in the aggressive zone.
    public static final int TREE_SNAP_DISTANCE = 8;
    // Distance within which bat swarms are flushed on chunk entry.
    public static final int BAT_FLUSH_DISTANCE = 32;
    // Number of bats in each flush swarm.
    public static final int BAT_SWARM_COUNT = 12;
    // Distance at which snowdrift accumulation runs.
    public static final int SNOWDRIFT_DISTANCE = 32;
    // Ticks between frostbite respawn debuff duration (= 3 real minutes).
    public static final int FROSTBITE_DURATION_TICKS = 20 * 60 * 3;

    // Villager communities — caravan evacuation system.
    // How often (ticks) the world is scanned for new villager communities.
    public static final int VILLAGE_SCAN_INTERVAL_TICKS = 200;
    // Blocks ahead of the wall front to scan for villagers.
    public static final int VILLAGE_SCAN_DISTANCE = 160;
    // Villagers within this many blocks of each other form one community.
    public static final int VILLAGE_CLUSTER_RADIUS = 48;
    // Minimum villager count to be recognised as a community.
    public static final int VILLAGE_MIN_SIZE = 2;
    // Wall must reach within this many blocks of a community to trigger evacuation.
    public static final int VILLAGE_EVACUATION_DISTANCE = 80;
    // Blocks south of the wall that count as "safely escaped".
    public static final int VILLAGE_SAFE_DISTANCE = 160;
    // Z-velocity added to fleeing villagers every 5 ticks.
    public static final double VILLAGE_FLEE_NUDGE = 0.14;

    private IceWallConfig() {
    }
}