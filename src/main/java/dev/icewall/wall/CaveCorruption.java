package dev.icewall.wall;

import dev.icewall.config.IceWallConfig;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Underground-specific glacier effects, running every server tick alongside GlacierCorruption.
 *
 * Periodic area events (cryo-flood, stalactite barrage, magma freeze, sculk crystallisation,
 * glow lichen freeze, amethyst resonance) are delegated to {@link CaveAreaEvents}.
 */
public final class CaveCorruption {

    private final Random rng = new Random();
    private final HeatmapTracker heatmapTracker;
    private final CaveAreaEvents areaEvents = new CaveAreaEvents(rng);

    public CaveCorruption(HeatmapTracker tracker) {
        this.heatmapTracker = tracker;
    }

    public void tick(ServerLevel world, IceWallState state) {
        if (!state.isActive()) {
            return;
        }

        int wallZ = state.getWallFrontZ();
        int minX  = state.getMinExploredX();
        int maxX  = state.getMaxExploredX();
        if (minX > maxX) {
            return;
        }

        int width = maxX - minX + 1;
        int minY  = world.getMinY();

        // --- Terrain band pass ---
        for (int i = 0; i < IceWallConfig.CAVE_TERRAIN_SAMPLES; i++) {
            int x = minX + rng.nextInt(width);
            int z = wallZ + rng.nextInt(IceWallConfig.CORRUPTION_RANGE);
            int distAhead = z - wallZ;

            double chance = 1.0 - (double) distAhead / IceWallConfig.CORRUPTION_RANGE;
            if (rng.nextDouble() > chance) {
                continue;
            }

            if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) {
                continue;
            }

            int surfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            int range = surfaceY - minY - 4;
            if (range < 1) {
                continue;
            }

            int y = minY + rng.nextInt(range);
            applyCaveEffect(world, x, y, z, distAhead);
        }

        // --- Player proximity pass (underground players only) ---
        for (ServerPlayer player : world.players()) {
            if (player.isSpectator()) {
                continue;
            }

            BlockPos pPos = player.blockPosition();
            int distAhead = pPos.getZ() - wallZ;
            if (distAhead <= 0 || distAhead > IceWallConfig.CORRUPTION_RANGE) {
                continue;
            }

            int surfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE, pPos.getX(), pPos.getZ());
            if (pPos.getY() >= surfaceY - 4) {
                continue;
            }

            double intensity = 1.0 - (double) distAhead / IceWallConfig.CORRUPTION_RANGE;
            int radius = IceWallConfig.CAVE_PLAYER_RADIUS;

            for (int i = 0; i < IceWallConfig.CAVE_PLAYER_SAMPLES; i++) {
                if (rng.nextDouble() > intensity) {
                    continue;
                }
                int sx = pPos.getX() + rng.nextInt(radius * 2 + 1) - radius;
                int sy = pPos.getY() + rng.nextInt(radius * 2 + 1) - radius;
                int sz = pPos.getZ() + rng.nextInt(radius * 2 + 1) - radius;
                if (sy < world.getMinY() || sy >= world.getMaxY()) {
                    continue;
                }
                applyCaveEffect(world, sx, sy, sz, sz - wallZ);
            }

            // Cave rumble
            if (distAhead <= IceWallConfig.CAVE_SOUND_DISTANCE
                    && (world.getGameTime() + player.getId()) % IceWallConfig.CAVE_SOUND_INTERVAL == 0L) {
                float volume = (float) (1.0 - (double) distAhead / IceWallConfig.CAVE_SOUND_DISTANCE) * 0.8f + 0.2f;
                float pitch  = 0.25f + rng.nextFloat() * 0.15f;
                world.playSound(null, pPos, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, volume, pitch);
            }

            // Darkness pulse
            if (distAhead <= IceWallConfig.CAVE_SOUND_DISTANCE
                    && (world.getGameTime() + player.getId()) % IceWallConfig.DARKNESS_INTERVAL == 0L) {
                player.addEffect(new MobEffectInstance(
                        MobEffects.DARKNESS, IceWallConfig.DARKNESS_DURATION, 0, false, false));
                if (distAhead < 24) {
                    player.connection.send(new ClientboundSetActionBarTextPacket(
                            Component.literal("\u00a78\u00a7o\u258c The cave goes pitch black...")));
                }
            }

            // Cave gas pocket
            if (distAhead < IceWallConfig.CAVE_GAS_DISTANCE && rng.nextInt(200) == 0) {
                boolean isSevere = distAhead < 16;
                player.addEffect(new MobEffectInstance(
                        isSevere ? MobEffects.WITHER : MobEffects.POISON,
                        isSevere ? 60 : 100, 0, false, true));
                player.addEffect(new MobEffectInstance(MobEffects.HUNGER, 200, 0, false, false));
                player.connection.send(new ClientboundSetActionBarTextPacket(
                        Component.literal("\u00a72\u00a7o\u2697 A pocket of trapped glacial gas...").append(
                        Component.literal(isSevere ? " \u00a74\u00a7lWITHER!" : " \u00a7aPoisoned!").withStyle(
                                isSevere ? net.minecraft.ChatFormatting.DARK_RED
                                         : net.minecraft.ChatFormatting.GREEN))));
                world.playSound(null, pPos, SoundEvents.LAVA_POP, SoundSource.BLOCKS, 1.0f, 0.4f);
            }
        }

        // Periodic area events (not per-player)
        areaEvents.tick(world, wallZ, minX, maxX);
    }

    private void applyCaveEffect(ServerLevel world, int x, int y, int z, int distAhead) {
        if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) {
            return;
        }
        if (y < world.getMinY() || y >= world.getMaxY()) {
            return;
        }
        if (distAhead < 0) {
            return;
        }

        // Permafrost: at deep Y levels, stone slowly becomes packed ice.
        if (y <= IceWallConfig.PERMAFROST_MAX_Y) {
            BlockState deep = world.getBlockState(new BlockPos(x, y, z));
            if (CaveAreaEvents.isStoneVariant(deep) && rng.nextInt(3) == 0) {
                world.setBlock(new BlockPos(x, y, z), Blocks.PACKED_ICE.defaultBlockState(), Block.UPDATE_CLIENTS);
                return;
            }
        }

        BlockPos pos   = new BlockPos(x, y, z);
        BlockState st  = world.getBlockState(pos);

        // 1. Torch / lantern / campfire snuffing
        if (distAhead < IceWallConfig.CORRUPTION_KILL_VEGETATION_DISTANCE && isLightSource(st)) {
            world.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            return;
        }

        // 2. Lava sealing
        if (st.getBlock() == Blocks.LAVA) {
            world.setBlock(pos, Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_CLIENTS);
            return;
        }

        if (st.isAir()) {
            // 3. Ceiling collapse
            if (distAhead < IceWallConfig.CAVE_COLLAPSE_DISTANCE && y + 1 < world.getMaxY()
                    && rng.nextInt(5) == 0) {
                BlockPos above = pos.above();
                if (CaveAreaEvents.isCollapsibleStone(world.getBlockState(above))) {
                    world.setBlock(above, Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
            // Stalactite spears
            if (distAhead < IceWallConfig.STALACTITE_DISTANCE && y + 1 < world.getMaxY()
                    && rng.nextInt(4) == 0) {
                BlockPos above = pos.above();
                if (CaveAreaEvents.isCollapsibleStone(world.getBlockState(above))) {
                    BlockState stalactite = Blocks.POINTED_DRIPSTONE.defaultBlockState()
                            .setValue(BlockStateProperties.VERTICAL_DIRECTION, Direction.DOWN);
                    world.setBlock(pos, stalactite, Block.UPDATE_CLIENTS);
                }
            }
        } else if (distAhead >= 16 && distAhead <= 48 && CaveAreaEvents.isStoneVariant(st)) {
            // 4. Ice vein infiltration
            if (CaveAreaEvents.isAdjacentToAir(world, pos) && rng.nextInt(3) == 0) {
                BlockState iceVariant = (rng.nextInt(3) == 0)
                    ? Blocks.BLUE_ICE.defaultBlockState()
                    : Blocks.PACKED_ICE.defaultBlockState();
                world.setBlock(pos, iceVariant, Block.UPDATE_CLIENTS);
            }
        }
    }

    private static boolean isLightSource(BlockState state) {
        Block b = state.getBlock();
        return b == Blocks.TORCH
            || b == Blocks.WALL_TORCH
            || b == Blocks.SOUL_TORCH
            || b == Blocks.SOUL_WALL_TORCH
            || b == Blocks.LANTERN
            || b == Blocks.SOUL_LANTERN
            || b == Blocks.CAMPFIRE
            || b == Blocks.SOUL_CAMPFIRE;
    }
}
