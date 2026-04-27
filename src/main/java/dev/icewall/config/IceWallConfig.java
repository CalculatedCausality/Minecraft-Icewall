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

    // New cave effects.
    // Cryo-flood: radius of powder-snow fill around a random low cave point.
    public static final int CRYO_FLOOD_RADIUS    = 10;
    // Cave gas: distance ahead in which underground gas-pocket Poison/Wither is applied.
    public static final int CAVE_GAS_DISTANCE    = 40;
    // Darkness pulse: interval between Darkness applications to underground players.
    public static final int DARKNESS_INTERVAL    = 60;
    // Darkness duration per pulse (ticks).
    public static final int DARKNESS_DURATION    = 80;
    // Stalactite barrage: how many dripstone spears to form per barrage event.
    public static final int STALA_BARRAGE_COUNT  = 20;
    // Sculk vein spread distance (blocks ahead of wall).
    public static final int SCULK_SPREAD_DISTANCE = 48;
    // Glow lichen freeze: blocks ahead of wall within which lichen is replaced.
    public static final int LICHEN_FREEZE_DISTANCE = 32;
    // Amethyst resonance chance denominator (1-in-N amethyst cluster → shatters).
    public static final int AMETHYST_SHATTER_CHANCE = 6;

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
    // Distance at which light snowfall begins (before the full blizzard kicks in).
    public static final int SNOW_ONSET_DISTANCE    = 600;
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

    // Village rebuild — blocks placed per tick during visible reconstruction (lower = slower/more dramatic).
    public static final int VILLAGE_BUILD_BLOCKS_PER_TICK = 2;

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

    // Natural disasters — triggered randomly when the wall is active.
    // Average ticks between a random disaster check (geometric distribution).
    public static final int DISASTER_CHECK_INTERVAL = 400;
    // 1-in-N chance per check that a disaster fires (so expected gap ≈ INTERVAL * N).
    public static final int DISASTER_CHANCE_DENOMINATOR = 3;
    // Maximum blocks ahead/behind the wall that disasters target.
    public static final int DISASTER_RANGE_AHEAD = 60;
    public static final int DISASTER_RANGE_BEHIND = 40;

    // Avalanche — gravel/snow cascade column count and height drop.
    public static final int AVALANCHE_COLUMNS = 40;
    public static final int AVALANCHE_HEIGHT   = 6;

    // Ice meteor shower — number of packed-ice projectiles per event.
    public static final int METEOR_COUNT = 12;
    public static final int METEOR_HEIGHT = 40; // blocks above surface to spawn FallingBlock

    // Permafrost heave — surface column push-up.
    public static final int HEAVE_COLUMNS  = 24;
    public static final int HEAVE_MAX_RISE = 3; // maximum extra blocks pushed up

    // Earthquake — force applied to players.
    public static final double QUAKE_LAUNCH_STRENGTH = 0.5;
    // Number of cave-in columns triggered.
    public static final int QUAKE_COLLAPSE_COLUMNS = 60;

    // Frozen geyser — ice spire burst upward.
    public static final int GEYSER_COUNT   = 5;
    public static final int GEYSER_HEIGHT  = 8;

    // Blizzard surge — effect duration on players (ticks).
    public static final int SURGE_DURATION_TICKS = 20 * 25;

    // Ground crack — trench length and width.
    public static final int CRACK_LENGTH = 32;
    public static final int CRACK_DEPTH  = 5;

    // Ice rain — packed-ice blocks dropped from above.
    public static final int ICE_RAIN_COUNT = 20;
    public static final int ICE_RAIN_HEIGHT = 30;

    // Hypothermic wave — cold damage per hit to nearby mobs.
    public static final float HYPO_WAVE_DAMAGE = 4.0f;
    public static final int   HYPO_WAVE_RADIUS = 48;

    // Frost snap — snuffs torches/campfires in a radius.
    public static final int FROST_SNAP_RADIUS = 64;

    // Snowdrift tsunami — rapid snow-layer advance distance.
    public static final int SNOW_TSUNAMI_DISTANCE = 80;
    public static final int SNOW_TSUNAMI_COLUMNS  = 64;

    // Structural collapse — floating stone detection radius.
    public static final int COLLAPSE_RADIUS = 32;
    public static final int COLLAPSE_SAMPLES = 128;

    // Magnetic pulse duration (ticks).
    public static final int MAGNETIC_PULSE_DURATION = 20 * 12;

    // Aurora borealis — notification range.
    public static final int AURORA_NOTIFY_RANGE = 512;

    // -----------------------------------------------------------------------
    // New systems (session 3)
    // -----------------------------------------------------------------------

    // Glacial whispers — atmospheric lore messages.
    // Distance tiers (blocks ahead of wall).
    public static final int WHISPER_DISTANCE_CRITICAL = 100;  // < this → tier 2 (dramatic subtitle)
    public static final int WHISPER_DISTANCE_NEAR     = 300;  // < this → tier 1 (actionbar urgent)
    // Minimum ticks between whispers per player per tier.
    public static final int WHISPER_INTERVAL_CRITICAL = 100;
    public static final int WHISPER_INTERVAL_NEAR     = 200;
    public static final int WHISPER_INTERVAL_FAR      = 400;

    // Animal panic — passive mobs flee northward within this distance.
    public static final int ANIMAL_PANIC_DISTANCE = 100;

    // Ice pillar bloom — dramatic eruption of tall ice columns.
    // 1-in-N chance per tick to trigger a bloom.
    public static final int ICE_BLOOM_CHANCE        = 200;
    // Minimum blocks ahead of wall for the bloom cluster.
    public static final int ICE_BLOOM_DIST_MIN      = 10;
    // Random extra range added to minimum (actual = MIN + rand(RANGE)).
    public static final int ICE_BLOOM_DIST_RANGE    = 50;
    // Number of individual pillars in each bloom.
    public static final int ICE_BLOOM_PILLARS       = 7;
    // XZ spread radius of the pillar cluster.
    public static final int ICE_BLOOM_SPREAD        = 8;
    // Minimum pillar height.
    public static final int ICE_BLOOM_HEIGHT_MIN    = 8;
    // Random extra height (actual = MIN + rand(RANGE)).
    public static final int ICE_BLOOM_HEIGHT_RANGE  = 10;

    // Glass shatter — blocks ahead of wall within which glass shatters.
    public static final int GLASS_SHATTER_DISTANCE = 30;

    // -----------------------------------------------------------------------
    // New systems (session 4)
    // -----------------------------------------------------------------------

    // Armor frost drain — durability lost per tick inside the hypothermia zone.
    // Ticks between each drain event per player.
    public static final int ARMOR_DRAIN_INTERVAL_TICKS = 200;
    // Maximum durability points lost per drain event (at the wall face; scaled by closeness).
    public static final int ARMOR_DRAIN_PER_TICK = 2;

    // Portal sealing — scan / seal interval in ticks.
    public static final int PORTAL_SCAN_INTERVAL_TICKS = 40;
    // Blocks ahead of wall within which Nether portals are sealed.
    public static final int PORTAL_SEAL_DISTANCE = 80;

    // Stray hunting party — periodic Stray squad spawned at the wall face.
    // Ticks between spawn events.
    public static final int STRAY_SPAWN_INTERVAL_TICKS = 600;
    // Strays per squad.
    public static final int STRAY_PACK_SIZE = 4;
    // Blocks ahead of wall within which players are considered valid hunt targets.
    public static final int STRAY_HUNT_DISTANCE = 200;

    // -----------------------------------------------------------------------
    // New systems (session 5)
    // -----------------------------------------------------------------------

    // Item entity magnetism — dropped items pulled toward the wall.
    // Ticks between pull pulses.
    public static final int ITEM_MAGNET_INTERVAL_TICKS = 10;
    // Blocks ahead of wall within which items are attracted.
    public static final int ITEM_MAGNET_DISTANCE = 40;
    // Base velocity added toward the wall per pulse (scales with proximity).
    public static final double ITEM_MAGNET_STRENGTH = 0.12;

    // Indoor icicle drop — FallingBlockEntity dripstone falling on sheltered players.
    // Ticks between per-player icicle checks.
    public static final int ICICLE_CHECK_INTERVAL_TICKS = 40;
    // 1-in-N chance per check that an icicle is spawned.
    public static final int ICICLE_CHANCE_DENOMINATOR = 6;
    // How many blocks above the player to scan for a ceiling.
    public static final int ICICLE_SCAN_HEIGHT = 10;
    // Damage dealt per block fallen (FallingBlockEntity.setHurtsEntities param 1).
    public static final float ICICLE_DAMAGE_PER_BLOCK = 0.5f;
    // Max damage cap from a single icicle (param 2).
    public static final int ICICLE_MAX_DAMAGE = 8;

    // Cold snap hunger drain — saturation drained per HYPOTHERMIA check interval
    // at maximum cold intensity (fraction 1.0 = right at the wall face).
    public static final float COLD_SATURATION_DRAIN_MAX = 1.5f;

    // Wolf flight — distance ahead of the wall within which tamed wolves flee.
    public static final int WOLF_FLIGHT_DISTANCE = 80;

    // Frozen chest reroll — how often to scan for chests in glaciated territory.
    public static final int CHEST_REROLL_INTERVAL_TICKS = 800;
    // How many blocks behind the wall face to scan for chests.
    public static final int CHEST_REROLL_SCAN_DEPTH = 200;

    // Survivor leaderboard — how often to broadcast the ranking (ticks).
    public static final int LEADERBOARD_INTERVAL_TICKS = 6000; // ~5 minutes
    // How many entries to show.
    public static final int LEADERBOARD_MAX_ENTRIES = 5;

    // -----------------------------------------------------------------------
    // Rail network
    // -----------------------------------------------------------------------

    // Minimum blocks the rail head must stay ahead of the glacier face.
    public static final int RAIL_MIN_AHEAD_DISTANCE = 40;
    // Blocks of rail laid per scheduled extension event.
    public static final int RAIL_EXTEND_BATCH_SIZE = 12;
    // Ticks between scheduled extension pulses (~10 s).
    public static final int RAIL_EXTEND_INTERVAL_TICKS = 200;
    // Ticks between supply-cart dispatches (~2 min).
    public static final int RAIL_MINECART_INTERVAL_TICKS = 2400;
    // Blocks ahead of wall face where supply carts are placed.
    public static final int RAIL_CART_SPAWN_OFFSET = 60;
    // Ticks between worker crew checks (~30 s).
    public static final int RAIL_WORKER_MANAGE_INTERVAL_TICKS = 600;
    // Number of Rail Crew villagers to maintain near the construction head.
    public static final int RAIL_WORKER_COUNT = 3;
    // Maximum blocks a worker may stray from the head before being teleported back.
    public static final int RAIL_WORKER_MAX_WANDER = 32;
    // Player distance from wall (blocks) within which supply-cart announcements are sent.
    public static final int RAIL_ANNOUNCEMENT_DISTANCE = 500;
    // Maximum east/west drift of the generated rail route from its base X.
    public static final int RAIL_MEANDER_AMPLITUDE = 14;
    // Larger values make the route curve more gradually.
    public static final int RAIL_MEANDER_WAVELENGTH = 42;
    // Maximum natural height change before the rail crew builds bridge decking.
    public static final int RAIL_MAX_NATURAL_GRADE = 2;
    // Spacing between powered rail booster blocks.
    public static final int RAIL_POWERED_INTERVAL_BLOCKS = 24;
    // Distance between generated waystations along the rail route.
    public static final int RAIL_STATION_INTERVAL_BLOCKS = 96;
    // Length of each waystation storage siding, measured away from the main line.
    public static final int RAIL_STATION_SIDING_LENGTH = 4;
    // How often the rail crew scans existing track for broken/missing rail.
    public static final int RAIL_REPAIR_INTERVAL_TICKS = 300;
    // How many Z positions are checked per repair pass.
    public static final int RAIL_REPAIR_BATCH_SIZE = 32;

    // -----------------------------------------------------------------------
    // Respawn safety
    // -----------------------------------------------------------------------

    // Minimum blocks ahead of the glacier wall that the world spawn and safe
    // respawn teleport target must stay.  Also used as the threshold below which
    // a player's individual bed/anchor spawn is considered consumed.
    public static final int RESPAWN_SAFE_BUFFER = 30;
    // How often to scan player respawn configs for consumed spawn points (ticks).
    public static final int RESPAWN_SCAN_INTERVAL_TICKS = 400;

    private IceWallConfig() {
    }
}