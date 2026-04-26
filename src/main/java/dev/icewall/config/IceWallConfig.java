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

    private IceWallConfig() {
    }
}