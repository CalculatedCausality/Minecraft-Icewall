package dev.icewall.wall;

import dev.icewall.config.IceWallConfig;
import java.util.Random;
import net.minecraft.server.level.ServerLevel;

/**
 * Fifteen random natural disasters triggered by the advancing glacier.
 *
 *  1.  Avalanche             — gravel/snow cascade down a random surface strip
 *  2.  Ice meteor shower     — FallingBlockEntity packed-ice chunks from sky
 *  3.  Permafrost heave      — ground columns pushed up 1–3 blocks
 *  4.  Glacial earthquake    — cave-ins + player launch + rumble
 *  5.  Frozen geyser         — ice spires erupt from the earth
 *  6.  Blizzard surge        — Slowness III + Mining Fatigue II on all players
 *  7.  Ground crack          — long trench ripped through the surface
 *  8.  Ice rain              — FallingBlockEntity blue-ice drops in a wide field
 *  9.  Hypothermic wave      — burst cold damage to all nearby living entities
 * 10.  Aurora borealis       — light-show announcement + particle spray
 * 11.  Magnetic pulse        — compass scramble + Blindness + Nausea simultaneously
 * 12.  Glacial flood         — water burst then instant-freeze from a random point
 * 13.  Structural collapse   — floating stone columns drop as gravel
 * 14.  Frost snap            — snuffs all torches and campfires in a wide radius
 * 15.  Snowdrift tsunami     — rapid powder-snow advance far ahead of the wall
 *
 * Trigger implementations live in {@link DisasterTriggers}.
 */
public final class NaturalDisasters {

    private static final DisasterType[] DISASTER_TYPES = DisasterType.values();

    private final Random rng = new Random();
    private final DisasterTriggers triggers = new DisasterTriggers(rng);

    private enum DisasterType {
        AVALANCHE, ICE_METEOR_SHOWER, PERMAFROST_HEAVE, EARTHQUAKE, FROZEN_GEYSER,
        BLIZZARD_SURGE, GROUND_CRACK, ICE_RAIN, HYPOTHERMIC_WAVE, AURORA_BOREALIS,
        MAGNETIC_PULSE, GLACIAL_FLOOD, STRUCTURAL_COLLAPSE, FROST_SNAP, SNOWDRIFT_TSUNAMI
    }

    // -----------------------------------------------------------------------
    // Public tick
    // -----------------------------------------------------------------------

    public void tick(ServerLevel world, IceWallState state) {
        if (!state.isActive()) return;
        if (rng.nextInt(IceWallConfig.DISASTER_CHECK_INTERVAL) != 0) return;
        if (rng.nextInt(IceWallConfig.DISASTER_CHANCE_DENOMINATOR) != 0) return;

        DisasterType type = DISASTER_TYPES[rng.nextInt(DISASTER_TYPES.length)];
        trigger(type, world, state);
    }

    // -----------------------------------------------------------------------
    // Dispatch
    // -----------------------------------------------------------------------

    private void trigger(DisasterType type, ServerLevel world, IceWallState state) {
        int wallZ = state.getWallFrontZ();
        int minX  = state.getMinExploredX();
        int maxX  = state.getMaxExploredX();
        if (minX >= maxX) return;

        switch (type) {
            case AVALANCHE           -> triggers.triggerAvalanche(world, wallZ, minX, maxX);
            case ICE_METEOR_SHOWER   -> triggers.triggerMeteorShower(world, wallZ, minX, maxX);
            case PERMAFROST_HEAVE    -> triggers.triggerPermafrostHeave(world, wallZ, minX, maxX);
            case EARTHQUAKE          -> triggers.triggerEarthquake(world, wallZ, minX, maxX);
            case FROZEN_GEYSER       -> triggers.triggerFrozenGeyser(world, wallZ, minX, maxX);
            case BLIZZARD_SURGE      -> triggers.triggerBlizzardSurge(world, wallZ);
            case GROUND_CRACK        -> triggers.triggerGroundCrack(world, wallZ, minX, maxX);
            case ICE_RAIN            -> triggers.triggerIceRain(world, wallZ, minX, maxX);
            case HYPOTHERMIC_WAVE    -> triggers.triggerHypothermicWave(world, wallZ, minX, maxX);
            case AURORA_BOREALIS     -> triggers.triggerAurora(world, wallZ);
            case MAGNETIC_PULSE      -> triggers.triggerMagneticPulse(world, wallZ);
            case GLACIAL_FLOOD       -> triggers.triggerGlacialFlood(world, wallZ, minX, maxX);
            case STRUCTURAL_COLLAPSE -> triggers.triggerStructuralCollapse(world, wallZ, minX, maxX);
            case FROST_SNAP          -> triggers.triggerFrostSnap(world, wallZ, minX, maxX);
            case SNOWDRIFT_TSUNAMI   -> triggers.triggerSnowdriftTsunami(world, wallZ, minX, maxX);
        }
    }
}