package dev.icewall.wall;

import dev.icewall.config.IceWallConfig;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.level.ChunkPos;

/**
 * Tracks per-chunk corruption sample counts over a rolling time window.
 * Used by the /icewall heatmap command to visualise where the glacier is most
 * aggressively corrupting the terrain.
 *
 * Keys are packed chunk coords from {@link ChunkPos#pack(int, int)}.
 * Counts are halved (decayed) once per HEATMAP_WINDOW_TICKS to produce a
 * soft rolling average rather than an ever-growing counter.
 */
public final class HeatmapTracker {

    private final Map<Long, Integer> counts = new HashMap<>();
    private long lastDecayTick = 0L;

    /** Record one sample at block position (x, z). */
    public void record(int x, int z) {
        long key = ChunkPos.pack(x >> 4, z >> 4);
        counts.merge(key, 1, Integer::sum);
    }

    /** Halve all counts once per window; called every tick by GlacierCorruption. */
    public void maybeDecay(long gameTime) {
        if (gameTime - lastDecayTick < IceWallConfig.HEATMAP_WINDOW_TICKS) {
            return;
        }
        lastDecayTick = gameTime;
        counts.replaceAll((k, v) -> v / 2);
        counts.values().removeIf(v -> v == 0);
    }

    /** Returns an unmodifiable view of the current chunk-key → count map. */
    public Map<Long, Integer> getCounts() {
        return Collections.unmodifiableMap(counts);
    }
}
