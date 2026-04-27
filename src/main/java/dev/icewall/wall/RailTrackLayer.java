package dev.icewall.wall;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Stateless block-placement helpers shared by the rail-network subsystems.
 *
 * Extracts the repetitive geometry utilities from {@link GlacierRailNetwork}
 * so the orchestrator class stays focused on scheduling and state.
 */
public final class RailTrackLayer {

    // -----------------------------------------------------------------------
    // Rail space
    // -----------------------------------------------------------------------

    public static void clearRailSpace(ServerLevel world, BlockPos railPos) {
        BlockState existing = world.getBlockState(railPos);
        if (existing.isAir()
                || existing.getBlock() == Blocks.SNOW
                || existing.getBlock() == Blocks.SHORT_GRASS
                || existing.getBlock() == Blocks.TALL_GRASS) {
            return;
        }
        world.setBlock(railPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    public static void placeRepairRail(ServerLevel world, int x, int z) {
        int surfaceY = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos deckPos = new BlockPos(x, surfaceY - 1, z);
        if (!world.getBlockState(deckPos).isSolid()) {
            world.setBlock(deckPos, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        BlockPos railPos = deckPos.above();
        clearRailSpace(world, railPos);
        world.setBlock(railPos, Blocks.RAIL.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    // -----------------------------------------------------------------------
    // Bridge decking
    // -----------------------------------------------------------------------

    public static void buildBridgeDeck(ServerLevel world, BlockPos deckPos, int surfaceY, boolean connector) {
        world.setBlock(deckPos, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_CLIENTS);
        if (!connector) {
            placeBridgeEdge(world, deckPos.east());
            placeBridgeEdge(world, deckPos.west());
        } else {
            placeBridgeEdge(world, deckPos.north());
            placeBridgeEdge(world, deckPos.south());
        }
        int supportBottom = Math.max(world.getMinY(), surfaceY - 1);
        for (int y = deckPos.getY() - 1; y >= supportBottom; y--) {
            BlockPos support = new BlockPos(deckPos.getX(), y, deckPos.getZ());
            if (world.getBlockState(support).isSolid()) break;
            world.setBlock(support, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    private static void placeBridgeEdge(ServerLevel world, BlockPos pos) {
        if (world.getBlockState(pos).isAir()) {
            world.setBlock(pos, Blocks.OAK_SLAB.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    // -----------------------------------------------------------------------
    // Station decking
    // -----------------------------------------------------------------------

    public static void placeStationDeck(ServerLevel world, BlockPos deck) {
        world.setBlock(deck, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_CLIENTS);
        for (int y = deck.getY() - 1; y >= Math.max(world.getMinY(), deck.getY() - 8); y--) {
            BlockPos support = new BlockPos(deck.getX(), y, deck.getZ());
            if (world.getBlockState(support).isSolid()) break;
            world.setBlock(support, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    public static void placeStationTorch(ServerLevel world, BlockPos pos) {
        if (world.getBlockState(pos).isAir()) {
            world.setBlock(pos, Blocks.TORCH.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    // -----------------------------------------------------------------------
    // Terrain helpers
    // -----------------------------------------------------------------------

    /**
     * Scans downward from just above the surface to locate a RAIL or POWERED_RAIL.
     * Returns the Y coordinate, or {@code -1} if not found.
     */
    public static int findRailY(ServerLevel world, int x, int z) {
        int topY = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 2;
        for (int y = topY; y >= world.getMinY(); y--) {
            Block block = world.getBlockState(new BlockPos(x, y, z)).getBlock();
            if (block == Blocks.RAIL || block == Blocks.POWERED_RAIL) return y;
        }
        return -1;
    }

    public static int moveToward(int current, int target, int maxStep) {
        if (current < target) return Math.min(current + maxStep, target);
        if (current > target) return Math.max(current - maxStep, target);
        return current;
    }

    private RailTrackLayer() {}
}
