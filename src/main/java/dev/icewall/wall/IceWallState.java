package dev.icewall.wall;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.icewall.IceWallMod;
import dev.icewall.config.IceWallConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class IceWallState extends SavedData {
    private static final Codec<IceWallState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.INT.fieldOf("startZ").forGetter(IceWallState::getStartZ),
        Codec.INT.fieldOf("wallFrontZ").forGetter(IceWallState::getWallFrontZ),
        Codec.INT.fieldOf("minExploredX").forGetter(IceWallState::getMinExploredX),
        Codec.INT.fieldOf("maxExploredX").forGetter(IceWallState::getMaxExploredX),
        Codec.LONG.fieldOf("tickAccumulator").forGetter(IceWallState::getTickAccumulator),
        Codec.BOOL.fieldOf("active").forGetter(IceWallState::isActive),
        Codec.BOOL.fieldOf("initialized").forGetter(IceWallState::isInitialized),
        Codec.BOOL.fieldOf("needsBootstrapSlice").forGetter(IceWallState::needsBootstrapSlice),
        Codec.INT.optionalFieldOf("advanceIntervalTicks", IceWallConfig.DEFAULT_ADVANCE_INTERVAL_TICKS).forGetter(IceWallState::getAdvanceIntervalTicks)
    ).apply(instance, IceWallState::new));
    private static final SavedDataType<IceWallState> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath(IceWallMod.MOD_ID, "state"),
        IceWallState::new,
        CODEC,
        DataFixTypes.LEVEL
    );

    private int startZ;
    private int wallFrontZ;
    private int minExploredX;
    private int maxExploredX;
    private long tickAccumulator;
    private boolean active;
    private boolean initialized;
    private boolean needsBootstrapSlice;
    private int advanceIntervalTicks = IceWallConfig.DEFAULT_ADVANCE_INTERVAL_TICKS;

    private IceWallState() {
    }

    private IceWallState(
        int startZ,
        int wallFrontZ,
        int minExploredX,
        int maxExploredX,
        long tickAccumulator,
        boolean active,
        boolean initialized,
        boolean needsBootstrapSlice,
        int advanceIntervalTicks
    ) {
        this.startZ = startZ;
        this.wallFrontZ = wallFrontZ;
        this.minExploredX = minExploredX;
        this.maxExploredX = maxExploredX;
        this.tickAccumulator = tickAccumulator;
        this.active = active;
        this.initialized = initialized;
        this.needsBootstrapSlice = needsBootstrapSlice;
        this.advanceIntervalTicks = advanceIntervalTicks;
    }

    public static IceWallState get(ServerLevel world) {
        return world.getDataStorage().computeIfAbsent(TYPE);
    }

    public void initializeIfNeeded(ServerLevel world) {
        if (initialized) {
            return;
        }

        BlockPos spawnPos = world.getLevelData().getRespawnData().pos();
        startZ = spawnPos.getZ() - IceWallConfig.START_OFFSET_BLOCKS;
        wallFrontZ = startZ;
        minExploredX = spawnPos.getX();
        maxExploredX = spawnPos.getX();
        tickAccumulator = 0L;
        active = true;
        initialized = true;
        needsBootstrapSlice = true;
        setDirty();
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
        setDirty();
    }

    public boolean consumeBootstrapSlice() {
        if (!needsBootstrapSlice) {
            return false;
        }

        needsBootstrapSlice = false;
        setDirty();
        return true;
    }

    public boolean advanceIfDue() {
        tickAccumulator += 1;
        if (tickAccumulator < advanceIntervalTicks) {
            return false;
        }

        tickAccumulator = 0L;
        wallFrontZ += 1;
        setDirty();
        return true;
    }

    public BoundExpansion recordLoadedChunk(ChunkPos chunkPos) {
        int oldMin = minExploredX;
        int oldMax = maxExploredX;
        int chunkMinX = chunkPos.getMinBlockX();
        int chunkMaxX = chunkPos.getMaxBlockX();

        if (chunkMinX < minExploredX) {
            minExploredX = chunkMinX;
        }
        if (chunkMaxX > maxExploredX) {
            maxExploredX = chunkMaxX;
        }

        if (minExploredX != oldMin || maxExploredX != oldMax) {
            setDirty();
        }

        XRange west = chunkMinX < oldMin ? new XRange(chunkMinX, oldMin - 1) : null;
        XRange east = chunkMaxX > oldMax ? new XRange(oldMax + 1, chunkMaxX) : null;
        return new BoundExpansion(west, east);
    }

    public int getStartZ() {
        return startZ;
    }

    public int getWallFrontZ() {
        return wallFrontZ;
    }

    public int getMinExploredX() {
        return minExploredX;
    }

    public int getMaxExploredX() {
        return maxExploredX;
    }

    public int getExploredWidth() {
        return (maxExploredX - minExploredX) + 1;
    }

    public long getTickAccumulator() {
        return tickAccumulator;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean needsBootstrapSlice() {
        return needsBootstrapSlice;
    }

    public int getAdvanceIntervalTicks() {
        return advanceIntervalTicks;
    }

    public void setAdvanceIntervalTicks(int ticks) {
        this.advanceIntervalTicks = Math.max(1, ticks);
        setDirty();
    }

    public void setWallFrontZ(int z) {
        this.wallFrontZ = z;
        this.tickAccumulator = 0L;
        setDirty();
    }

    public record XRange(int minX, int maxX) {
    }

    public record BoundExpansion(XRange west, XRange east) {
    }
}