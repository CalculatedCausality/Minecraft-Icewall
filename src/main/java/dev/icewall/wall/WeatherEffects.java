package dev.icewall.wall;

import dev.icewall.config.IceWallConfig;
import java.util.Random;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.phys.AABB;

/**
 * Handles all weather, sky, and environment effects tied to player proximity.
 *
 * Systems contained here:
 *   1.  Blizzard lock    — forced thunderstorm when wall within BLIZZARD_LOCK_DISTANCE
 *   2.  Whiteout fog     — Blindness pulses at WHITEOUT_DISTANCE
 *   3.  Lightning strikes — random bolts near the wall every ~LIGHTNING_INTERVAL_TICKS
 *   4.  Freeze wind push  — constant north/up knockback vector within WIND_PUSH_DISTANCE
 *   5.  Snowdrift         — snow layers stack up behind the wall over time
 *   6.  River flash-freeze — burst-freezes entire water chunks the moment the wall passes
 *   7.  Temperature HUD   — cosmetic °C actionbar readout scaling with proximity
 *   8.  Freeze heartbeat  — Freezing effect ticks sent without damage at <20 blocks
 *   9.  Frostbite scar    — Weakness I for 3 min applied on kill (called externally)
 */
public final class WeatherEffects {

    private final Random rng = new Random();
    private boolean blizzardActive = false;
    private final java.util.Set<Long> flashFrozenChunks = new java.util.HashSet<>();

    public void tick(ServerLevel world, IceWallState state) {
        if (!state.isActive()) {
            maybeStopBlizzard(world);
            return;
        }

        int wallZ = state.getWallFrontZ();
        boolean anyClose = false;

        for (ServerPlayer player : world.players()) {
            if (player.isSpectator()) continue;
            int dist = player.blockPosition().getZ() - wallZ;
            if (dist <= 0 || dist > IceWallConfig.BLIZZARD_LOCK_DISTANCE) continue;
            anyClose = true;

            tickWhiteout(world, player, dist);
            tickWindPush(player, dist);
            tickTemperatureHud(world, player, dist);
            tickFreezeHeartbeat(player, dist);
        }

        if (anyClose) {
            ensureBlizzard(world);
        } else {
            maybeStopBlizzard(world);
        }

        tickLightning(world, state);
        tickSnowdrift(world, state);
        tickFlashFreeze(world, state);
    }

    // -----------------------------------------------------------------------
    // 1. Blizzard lock
    // -----------------------------------------------------------------------

    private void ensureBlizzard(ServerLevel world) {
        if (!blizzardActive) {
            WeatherData wd = world.getWeatherData();
            wd.setRaining(true);
            wd.setRainTime(20 * 60 * 60);
            wd.setThundering(true);
            wd.setThunderTime(20 * 60 * 60);
            wd.setClearWeatherTime(0);
            wd.setDirty();
            blizzardActive = true;
        }
    }

    private void maybeStopBlizzard(ServerLevel world) {
        if (blizzardActive) {
            WeatherData wd = world.getWeatherData();
            wd.setRaining(false);
            wd.setRainTime(0);
            wd.setThundering(false);
            wd.setThunderTime(0);
            wd.setClearWeatherTime(20 * 60 * 20);
            wd.setDirty();
            blizzardActive = false;
        }
    }

    // -----------------------------------------------------------------------
    // 2. Whiteout fog (Blindness pulses)
    // -----------------------------------------------------------------------

    private void tickWhiteout(ServerLevel world, ServerPlayer player, int dist) {
        if (dist > IceWallConfig.WHITEOUT_DISTANCE) return;
        if (world.getGameTime() % IceWallConfig.WHITEOUT_INTERVAL_TICKS != 0L) return;
        player.addEffect(new MobEffectInstance(
                MobEffects.BLINDNESS, IceWallConfig.WHITEOUT_DURATION_TICKS, 0, false, false));
    }

    // -----------------------------------------------------------------------
    // 3. Lightning strikes
    // -----------------------------------------------------------------------

    private void tickLightning(ServerLevel world, IceWallState state) {
        if (rng.nextInt(IceWallConfig.LIGHTNING_INTERVAL_TICKS) != 0) return;
        int wallZ = state.getWallFrontZ();
        int minX = state.getMinExploredX();
        int maxX = state.getMaxExploredX();
        if (minX >= maxX) return;
        int x = minX + rng.nextInt(maxX - minX + 1);
        int z = wallZ - rng.nextInt(IceWallConfig.LIGHTNING_DISTANCE + 1);
        if (world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) return;
        int surfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        net.minecraft.world.entity.LightningBolt bolt =
                EntityType.LIGHTNING_BOLT.create(world, EntitySpawnReason.NATURAL);
        if (bolt == null) return;
        bolt.teleportTo(x + 0.5, (double) surfaceY, z + 0.5);
        world.addFreshEntity(bolt);
    }

    // -----------------------------------------------------------------------
    // 4. Freeze wind push
    // -----------------------------------------------------------------------

    private void tickWindPush(ServerPlayer player, int dist) {
        if (dist > IceWallConfig.WIND_PUSH_DISTANCE) return;
        if (player.onGround()) {
            double strength = 0.06 * (1.0 - (double) dist / IceWallConfig.WIND_PUSH_DISTANCE);
            player.setDeltaMovement(
                    player.getDeltaMovement().add(0, strength * 0.3, -strength));
        } else {
            double strength = 0.04 * (1.0 - (double) dist / IceWallConfig.WIND_PUSH_DISTANCE);
            player.setDeltaMovement(
                    player.getDeltaMovement().add(0, 0, -strength));
        }
    }

    // -----------------------------------------------------------------------
    // 5. Snowdrift accumulation
    // -----------------------------------------------------------------------

    private void tickSnowdrift(ServerLevel world, IceWallState state) {
        if (world.getGameTime() % 20L != 0L) return;
        int wallZ = state.getWallFrontZ();
        int minX  = state.getMinExploredX();
        int maxX  = state.getMaxExploredX();
        if (minX >= maxX) return;
        for (int i = 0; i < 8; i++) {
            int x = minX + rng.nextInt(maxX - minX + 1);
            int z = wallZ - 1 - rng.nextInt(IceWallConfig.SNOWDRIFT_DISTANCE);
            if (world.getChunk(x >> 4, z >> 4,
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) == null) continue;
            int surfaceY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            BlockPos top = new BlockPos(x, surfaceY, z);
            if (!world.getBlockState(top).isAir()) continue;
            BlockPos below = top.below();
            net.minecraft.world.level.block.state.BlockState bsBelow = world.getBlockState(below);
            if (bsBelow.isSolid() || bsBelow.getBlock() == net.minecraft.world.level.block.Blocks.SNOW) {
                world.setBlock(top, net.minecraft.world.level.block.Blocks.SNOW.defaultBlockState(),
                        net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            }
        }
    }

    // -----------------------------------------------------------------------
    // 6. River/ocean flash-freeze
    // -----------------------------------------------------------------------

    private void tickFlashFreeze(ServerLevel world, IceWallState state) {
        if (world.getGameTime() % 20L != 0L) return;
        int wallZ = state.getWallFrontZ();
        int minX  = state.getMinExploredX();
        int maxX  = state.getMaxExploredX();
        if (minX >= maxX) return;
        // Check each chunk column that the wall just consumed
        int chunkZ = (wallZ - 1) >> 4;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            long key = net.minecraft.world.level.ChunkPos.pack(cx, chunkZ);
            if (flashFrozenChunks.contains(key)) continue;
            if (world.getChunk(cx, chunkZ, ChunkStatus.FULL, false) == null) continue;
            int waterCount = 0;
            int bx = cx << 4;
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int sy = world.getHeight(Heightmap.Types.WORLD_SURFACE, bx + lx, (chunkZ << 4) + lz) - 1;
                    if (sy < world.getMinY()) continue;
                    if (!world.getBlockState(new BlockPos(bx + lx, sy, (chunkZ << 4) + lz))
                            .getFluidState().isEmpty()) waterCount++;
                }
            }
            // Only flash-freeze if this chunk is substantially water (river/ocean)
            if (waterCount < 32) continue;
            flashFrozenChunks.add(key);
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int ax = bx + lx;
                    int az = (chunkZ << 4) + lz;
                    int sy = world.getHeight(Heightmap.Types.WORLD_SURFACE, ax, az) - 1;
                    if (sy < world.getMinY()) continue;
                    BlockPos pos = new BlockPos(ax, sy, az);
                    if (!world.getBlockState(pos).getFluidState().isEmpty()) {
                        world.setBlock(pos,
                            Blocks.ICE.defaultBlockState(),
                                Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // 7. Temperature HUD
    // -----------------------------------------------------------------------

    private void tickTemperatureHud(ServerLevel world, ServerPlayer player, int dist) {
        if (dist > IceWallConfig.TEMPERATURE_HUD_DISTANCE) return;
        if ((world.getGameTime() + player.getId()) % IceWallConfig.TEMPERATURE_HUD_INTERVAL_TICKS != 0L) return;
        double fraction = 1.0 - (double) dist / IceWallConfig.TEMPERATURE_HUD_DISTANCE;
        int celsius = (int) Math.round(-40.0 * fraction);
        ChatFormatting colour = celsius > -10 ? ChatFormatting.YELLOW
                : celsius > -25 ? ChatFormatting.AQUA
                : ChatFormatting.DARK_AQUA;
        player.connection.send(new ClientboundSetActionBarTextPacket(
                Component.literal("\uD83C\uDF21 " + celsius + "\u00B0C").withStyle(colour)));
    }

    // -----------------------------------------------------------------------
    // 8. Freeze heartbeat (visual freeze effect, no damage)
    // -----------------------------------------------------------------------

    private void tickFreezeHeartbeat(ServerPlayer player, int dist) {
        if (dist > 20) return;
        // Set freeze ticks high enough to show the vignette overlay; reset after 5 ticks
        int currentFreeze = player.getTicksFrozen();
        if (currentFreeze < 100) {
            player.setTicksFrozen(140);
        }
    }

    // -----------------------------------------------------------------------
    // 9. Frostbite scar — called by IceWallAdvancer on glacier kill
    // -----------------------------------------------------------------------

    /**
     * Applies a Weakness I debuff for FROSTBITE_DURATION_TICKS to a freshly respawned player.
     * Call this from the kill handler, not on respawn, so the effect is present when they
     * re-enter the world.
     */
    public void applyFrostbiteScar(ServerPlayer player) {
        player.addEffect(new MobEffectInstance(
                MobEffects.WEAKNESS, IceWallConfig.FROSTBITE_DURATION_TICKS, 0, false, true));
    }
}
