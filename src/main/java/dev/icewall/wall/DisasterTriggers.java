package dev.icewall.wall;

import dev.icewall.config.IceWallConfig;
import java.util.List;
import java.util.Random;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * Implementations of the 15 natural disaster events triggered by
 * {@link NaturalDisasters}.
 *
 * Extracted so {@link NaturalDisasters} only contains the dispatch logic,
 * cooldown state, and {@link NaturalDisasters#tick} entry point.
 */
final class DisasterTriggers {

    private final Random rng;
    private long lastAuroraTick = -6000L;

    private static final String[] AURORA_COLOURS = {
        "§b", "§3", "§2", "§a", "§d", "§5", "§9", "§1", "§6"
    };

    DisasterTriggers(Random rng) {
        this.rng = rng;
    }

    // -----------------------------------------------------------------------
    // 1. Avalanche — cascading gravel/snow across a random surface strip
    // -----------------------------------------------------------------------

    void triggerAvalanche(ServerLevel world, int wallZ, int minX, int maxX) {
        int startX = randomX(minX, maxX);
        int width  = 8 + rng.nextInt(12);
        int zOffset = rng.nextInt(IceWallConfig.DISASTER_RANGE_AHEAD + 1);
        int centreZ = wallZ - zOffset;

        announceAll(world, wallZ,
                Component.literal("AVALANCHE!").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD),
                Component.literal("The glacier fractures — snow cascades!").withStyle(ChatFormatting.GRAY),
                200);

        for (int i = 0; i < IceWallConfig.AVALANCHE_COLUMNS; i++) {
            int x = startX + (i % width) - width / 2;
            int z = centreZ + rng.nextInt(16) - 8;
            if (!chunkLoaded(world, x, z)) continue;
            int topY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            Block fill = (rng.nextBoolean()) ? Blocks.GRAVEL : Blocks.SNOW_BLOCK;
            for (int dy = 0; dy < IceWallConfig.AVALANCHE_HEIGHT; dy++) {
                BlockPos pos = new BlockPos(x, topY + 1 + dy, z);
                if (world.getBlockState(pos).isAir()) {
                    world.setBlock(pos, fill.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        playSoundAround(world, wallZ, minX, maxX, SoundEvents.GENERIC_BIG_FALL, 3.0f, 0.6f);
    }

    // -----------------------------------------------------------------------
    // 2. Ice meteor shower — FallingBlockEntity packed-ice from the sky
    // -----------------------------------------------------------------------

    void triggerMeteorShower(ServerLevel world, int wallZ, int minX, int maxX) {
        announceAll(world, wallZ,
                Component.literal("ICE METEOR SHOWER!").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
                Component.literal("Chunks of glacier rain from above!").withStyle(ChatFormatting.WHITE),
                300);

        for (int i = 0; i < IceWallConfig.METEOR_COUNT; i++) {
            int x = randomX(minX, maxX);
            int z = wallZ - rng.nextInt(IceWallConfig.DISASTER_RANGE_AHEAD);
            if (!chunkLoaded(world, x, z)) continue;
            int surfY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            BlockPos spawnPos = new BlockPos(x, surfY + IceWallConfig.METEOR_HEIGHT, z);
            BlockState iceState = (rng.nextBoolean())
                    ? Blocks.PACKED_ICE.defaultBlockState()
                    : Blocks.BLUE_ICE.defaultBlockState();
            world.setBlock(spawnPos, iceState, Block.UPDATE_CLIENTS);
            FallingBlockEntity falling = FallingBlockEntity.fall(world, spawnPos, iceState);
            falling.setDeltaMovement(
                    (rng.nextDouble() - 0.5) * 0.3, -0.4,
                    (rng.nextDouble() - 0.5) * 0.3);
            world.addFreshEntity(falling);
            world.removeBlock(spawnPos, false);
        }
        playSoundAround(world, wallZ, minX, maxX, SoundEvents.GENERIC_BIG_FALL, 2.0f, 1.4f);
    }

    // -----------------------------------------------------------------------
    // 3. Permafrost heave — surface columns pushed up 1–3 blocks
    // -----------------------------------------------------------------------

    void triggerPermafrostHeave(ServerLevel world, int wallZ, int minX, int maxX) {
        announceAll(world, wallZ,
                Component.literal("PERMAFROST HEAVE!").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD),
                Component.literal("The frozen ground buckles and rises.").withStyle(ChatFormatting.GRAY),
                160);

        for (int i = 0; i < IceWallConfig.HEAVE_COLUMNS; i++) {
            int x = randomX(minX, maxX);
            int z = wallZ - rng.nextInt(IceWallConfig.DISASTER_RANGE_AHEAD + IceWallConfig.DISASTER_RANGE_BEHIND);
            if (!chunkLoaded(world, x, z)) continue;
            int topY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            if (topY <= world.getMinY()) continue;
            int rise = 1 + rng.nextInt(IceWallConfig.HEAVE_MAX_RISE);
            BlockState base = world.getBlockState(new BlockPos(x, topY, z));
            if (!base.isSolid()) continue;
            for (int dy = rise; dy >= 1; dy--) {
                world.setBlock(new BlockPos(x, topY + dy, z), base, Block.UPDATE_CLIENTS);
            }
        }
        playSoundAround(world, wallZ, minX, maxX, SoundEvents.POINTED_DRIPSTONE_LAND, 2.0f, 0.5f);
    }

    // -----------------------------------------------------------------------
    // 4. Glacial earthquake — cave-ins + player launch + action-bar shudder
    // -----------------------------------------------------------------------

    void triggerEarthquake(ServerLevel world, int wallZ, int minX, int maxX) {
        announceAll(world, wallZ,
                Component.literal("EARTHQUAKE!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                Component.literal("The glacier grinds against the bedrock!").withStyle(ChatFormatting.YELLOW),
                320);

        for (int i = 0; i < IceWallConfig.QUAKE_COLLAPSE_COLUMNS; i++) {
            int x = randomX(minX, maxX);
            int z = wallZ - rng.nextInt(IceWallConfig.DISASTER_RANGE_AHEAD + IceWallConfig.DISASTER_RANGE_BEHIND);
            if (!chunkLoaded(world, x, z)) continue;
            for (int y = world.getMinY() + 1; y < world.getMaxY(); y++) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState bs = world.getBlockState(pos);
                if (bs.getBlock() == Blocks.STONE && world.getBlockState(pos.below()).isAir()) {
                    world.setBlock(pos, Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        for (ServerPlayer player : world.players()) {
            int dist = (int) player.getZ() - wallZ;
            if (dist < -IceWallConfig.DISASTER_RANGE_BEHIND || dist > IceWallConfig.DISASTER_RANGE_AHEAD) continue;
            double dx = (rng.nextDouble() - 0.5) * IceWallConfig.QUAKE_LAUNCH_STRENGTH;
            double dy = IceWallConfig.QUAKE_LAUNCH_STRENGTH * 0.7;
            double dz = (rng.nextDouble() - 0.5) * IceWallConfig.QUAKE_LAUNCH_STRENGTH;
            player.setDeltaMovement(player.getDeltaMovement().add(dx, dy, dz));
            player.connection.send(new ClientboundSetActionBarTextPacket(
                    Component.literal("§8§o⚠ The ground shakes violently!")));
        }
        playSoundAround(world, wallZ, minX, maxX, SoundEvents.POINTED_DRIPSTONE_FALL, 3.0f, 0.3f);
    }

    // -----------------------------------------------------------------------
    // 5. Frozen geyser — packed-ice spires erupt from underground
    // -----------------------------------------------------------------------

    void triggerFrozenGeyser(ServerLevel world, int wallZ, int minX, int maxX) {
        for (int g = 0; g < IceWallConfig.GEYSER_COUNT; g++) {
            int x = randomX(minX, maxX);
            int z = wallZ - rng.nextInt(IceWallConfig.DISASTER_RANGE_AHEAD);
            if (!chunkLoaded(world, x, z)) continue;
            int surfY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            Block fill = (rng.nextBoolean()) ? Blocks.PACKED_ICE : Blocks.BLUE_ICE;
            for (int dy = 0; dy < IceWallConfig.GEYSER_HEIGHT; dy++) {
                world.setBlock(new BlockPos(x, surfY + dy, z),
                        fill.defaultBlockState(), Block.UPDATE_CLIENTS);
                if (dy < 3) {
                    for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                        BlockPos side = new BlockPos(x + d[0], surfY + dy, z + d[1]);
                        if (world.getBlockState(side).isAir())
                            world.setBlock(side, Blocks.PACKED_ICE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        announceAll(world, wallZ,
                Component.literal("FROZEN GEYSER!").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
                Component.literal("Ice erupts from the earth!").withStyle(ChatFormatting.WHITE),
                160);
        playSoundAround(world, wallZ, minX, maxX, SoundEvents.POINTED_DRIPSTONE_LAND, 2.5f, 1.2f);
    }

    // -----------------------------------------------------------------------
    // 6. Blizzard surge — Slowness III + Mining Fatigue II on all players
    // -----------------------------------------------------------------------

    void triggerBlizzardSurge(ServerLevel world, int wallZ) {
        announceAll(world, wallZ,
                Component.literal("BLIZZARD SURGE!").withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.BOLD),
                Component.literal("A wall of arctic wind pins you in place.").withStyle(ChatFormatting.WHITE),
                400);
        for (ServerPlayer player : world.players()) {
            int dist = (int) player.getZ() - wallZ;
            if (dist > IceWallConfig.DISASTER_RANGE_AHEAD) continue;
            player.addEffect(new MobEffectInstance(
                    MobEffects.SLOWNESS, IceWallConfig.SURGE_DURATION_TICKS, 2, false, true));
            player.addEffect(new MobEffectInstance(
                    MobEffects.MINING_FATIGUE, IceWallConfig.SURGE_DURATION_TICKS, 1, false, true));
        }
    }

    // -----------------------------------------------------------------------
    // 7. Ground crack — a long trench rips open across the surface
    // -----------------------------------------------------------------------

    void triggerGroundCrack(ServerLevel world, int wallZ, int minX, int maxX) {
        int startX = randomX(minX, maxX);
        int z      = wallZ - rng.nextInt(IceWallConfig.DISASTER_RANGE_AHEAD);
        announceAll(world, wallZ,
                Component.literal("GROUND CRACK!").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.BOLD),
                Component.literal("A fissure tears open across the frozen earth!").withStyle(ChatFormatting.GRAY),
                200);
        for (int dx = 0; dx < IceWallConfig.CRACK_LENGTH; dx++) {
            int x = startX + dx;
            int dz = rng.nextInt(3) - 1;
            int cz = z + dz;
            if (!chunkLoaded(world, x, cz)) continue;
            int topY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, cz) - 1;
            for (int depth = 0; depth < IceWallConfig.CRACK_DEPTH; depth++) {
                BlockPos pos = new BlockPos(x, topY - depth, cz);
                BlockState bs = world.getBlockState(pos);
                if (bs.isSolid()) {
                    world.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        playSoundAround(world, wallZ, minX, maxX, SoundEvents.POINTED_DRIPSTONE_FALL, 2.0f, 0.4f);
    }

    // -----------------------------------------------------------------------
    // 8. Ice rain — blue-ice FallingBlockEntity field
    // -----------------------------------------------------------------------

    void triggerIceRain(ServerLevel world, int wallZ, int minX, int maxX) {
        announceAll(world, wallZ,
                Component.literal("ICE RAIN!").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
                Component.literal("Shards of glacier ice fall like rain.").withStyle(ChatFormatting.WHITE),
                240);
        for (int i = 0; i < IceWallConfig.ICE_RAIN_COUNT; i++) {
            int x = randomX(minX, maxX);
            int z = wallZ - rng.nextInt(IceWallConfig.DISASTER_RANGE_AHEAD);
            if (!chunkLoaded(world, x, z)) continue;
            int surfY = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            BlockPos spawnPos = new BlockPos(x, surfY + IceWallConfig.ICE_RAIN_HEIGHT, z);
            BlockState bs = Blocks.BLUE_ICE.defaultBlockState();
            world.setBlock(spawnPos, bs, Block.UPDATE_CLIENTS);
            FallingBlockEntity falling = FallingBlockEntity.fall(world, spawnPos, bs);
            falling.setDeltaMovement(
                    (rng.nextDouble() - 0.5) * 0.2, -0.5,
                    (rng.nextDouble() - 0.5) * 0.2);
            world.addFreshEntity(falling);
            world.removeBlock(spawnPos, false);
        }
    }

    // -----------------------------------------------------------------------
    // 9. Hypothermic wave — burst cold damage to all living entities in range
    // -----------------------------------------------------------------------

    void triggerHypothermicWave(ServerLevel world, int wallZ, int minX, int maxX) {
        announceAll(world, wallZ,
                Component.literal("HYPOTHERMIC WAVE!").withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.BOLD),
                Component.literal("A pulse of deep cold radiates from the glacier.").withStyle(ChatFormatting.WHITE),
                300);
        AABB zone = new AABB(
                minX, world.getMinY(), wallZ - IceWallConfig.HYPO_WAVE_RADIUS,
                maxX, world.getMaxY(), wallZ + IceWallConfig.DISASTER_RANGE_AHEAD);
        List<LivingEntity> targets = world.getEntitiesOfClass(LivingEntity.class, zone,
                e -> !(e instanceof ServerPlayer));
        DamageSource freeze = world.damageSources().freeze();
        for (LivingEntity e : targets) {
            e.hurtServer(world, freeze, IceWallConfig.HYPO_WAVE_DAMAGE);
        }
        for (ServerPlayer player : world.players()) {
            double pz = player.getZ();
            if (pz < wallZ - IceWallConfig.HYPO_WAVE_RADIUS || pz > wallZ + IceWallConfig.DISASTER_RANGE_AHEAD) continue;
            player.setTicksFrozen(Math.min(player.getTicksFrozen() + 60, 140));
        }
        playSoundAround(world, wallZ, minX, maxX, SoundEvents.GENERIC_BIG_FALL, 1.5f, 0.5f);
    }

    // -----------------------------------------------------------------------
    // 10. Aurora borealis — light show title + coloured message
    // -----------------------------------------------------------------------

    void triggerAurora(ServerLevel world, int wallZ) {
        long now = world.getGameTime();
        if (now - lastAuroraTick < 6000L) return;
        lastAuroraTick = now;

        StringBuilder banner = new StringBuilder();
        for (String c : AURORA_COLOURS) banner.append(c).append("▓");
        String line = banner.toString();

        for (ServerPlayer player : world.players()) {
            double pz = player.getZ();
            if (pz < wallZ - IceWallConfig.AURORA_NOTIFY_RANGE
                    || pz > wallZ + IceWallConfig.AURORA_NOTIFY_RANGE) continue;
            player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 80, 30));
            player.connection.send(new ClientboundSetTitleTextPacket(
                    Component.literal(line + " AURORA BOREALIS " + line)
                            .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
            player.connection.send(new ClientboundSetSubtitleTextPacket(
                    Component.literal("The sky above the glacier blazes with colour.")
                            .withStyle(ChatFormatting.ITALIC, ChatFormatting.WHITE)));
        }
    }

    // -----------------------------------------------------------------------
    // 11. Magnetic pulse — Blindness + Nausea + compass scramble for players
    // -----------------------------------------------------------------------

    void triggerMagneticPulse(ServerLevel world, int wallZ) {
        announceAll(world, wallZ,
                Component.literal("MAGNETIC PULSE!").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
                Component.literal("Instruments fail — the world spins.").withStyle(ChatFormatting.GRAY),
                300);
        for (ServerPlayer player : world.players()) {
            int dist = (int) player.getZ() - wallZ;
            if (dist > IceWallConfig.DISASTER_RANGE_AHEAD || dist < -IceWallConfig.DISASTER_RANGE_BEHIND) continue;
            int dur = IceWallConfig.MAGNETIC_PULSE_DURATION;
            player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS,  40,  0, false, false));
            player.addEffect(new MobEffectInstance(MobEffects.NAUSEA,     dur, 0, false, true));
            player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS,   dur, 1, false, true));
            player.connection.send(new ClientboundSetActionBarTextPacket(
                    Component.literal("§5§k||||§r §5MAGNETIC PULSE §k||||§r")));
        }
    }

    // -----------------------------------------------------------------------
    // 12. Glacial flood — water burst then instant-freeze
    // -----------------------------------------------------------------------

    void triggerGlacialFlood(ServerLevel world, int wallZ, int minX, int maxX) {
        int cx = randomX(minX, maxX);
        int cz = wallZ - rng.nextInt(IceWallConfig.DISASTER_RANGE_AHEAD);
        if (!chunkLoaded(world, cx, cz)) return;
        int cy = world.getHeight(Heightmap.Types.WORLD_SURFACE, cx, cz);
        int radius = 8 + rng.nextInt(8);

        announceAll(world, wallZ,
                Component.literal("GLACIAL FLOOD!").withStyle(ChatFormatting.BLUE, ChatFormatting.BOLD),
                Component.literal("Meltwater bursts then freezes in an instant!").withStyle(ChatFormatting.AQUA),
                200);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz2 = -radius; dz2 <= radius; dz2++) {
                if (dx * dx + dz2 * dz2 > radius * radius) continue;
                int x = cx + dx, z = cz + dz2;
                if (!chunkLoaded(world, x, z)) continue;
                int sy = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                BlockPos pos = new BlockPos(x, sy, z);
                BlockState cur = world.getBlockState(pos);
                if (cur.isAir() || !cur.isSolid()) {
                    world.setBlock(pos, Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz2 = -radius; dz2 <= radius; dz2++) {
                if (dx * dx + dz2 * dz2 > radius * radius) continue;
                int x = cx + dx, z = cz + dz2;
                if (!chunkLoaded(world, x, z)) continue;
                int sy = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                BlockPos pos = new BlockPos(x, sy, z);
                if (!world.getBlockState(pos).getFluidState().isEmpty()) {
                    world.setBlock(pos, Blocks.ICE.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        playSoundAround(world, wallZ, minX, maxX, SoundEvents.GENERIC_SPLASH, 2.0f, 0.8f);
    }

    // -----------------------------------------------------------------------
    // 13. Structural collapse — floating stone/dirt drops as gravel/sand
    // -----------------------------------------------------------------------

    void triggerStructuralCollapse(ServerLevel world, int wallZ, int minX, int maxX) {
        announceAll(world, wallZ,
                Component.literal("STRUCTURAL COLLAPSE!").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.BOLD),
                Component.literal("The frozen ground can no longer hold.").withStyle(ChatFormatting.GRAY),
                200);
        int collapsed = 0;
        for (int s = 0; s < IceWallConfig.COLLAPSE_SAMPLES && collapsed < 32; s++) {
            int x  = randomX(minX, maxX);
            int z  = wallZ - rng.nextInt(IceWallConfig.DISASTER_RANGE_AHEAD + IceWallConfig.DISASTER_RANGE_BEHIND);
            int y0 = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            if (!chunkLoaded(world, x, z)) continue;
            for (int y = y0; y > world.getMinY() + 1; y--) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState bs = world.getBlockState(pos);
                if (!bs.isSolid()) continue;
                if (!world.getBlockState(pos.below()).isAir()) continue;
                Block replacement = bs.getBlock() == Blocks.DIRT ? Blocks.SAND : Blocks.GRAVEL;
                world.setBlock(pos, replacement.defaultBlockState(), Block.UPDATE_CLIENTS);
                collapsed++;
                break;
            }
        }
        playSoundAround(world, wallZ, minX, maxX, SoundEvents.POINTED_DRIPSTONE_FALL, 2.0f, 0.6f);
    }

    // -----------------------------------------------------------------------
    // 14. Frost snap — snuffs torches, campfires, lanterns in a wide radius
    // -----------------------------------------------------------------------

    void triggerFrostSnap(ServerLevel world, int wallZ, int minX, int maxX) {
        int snuffRadius = IceWallConfig.FROST_SNAP_RADIUS;
        int cx = randomX(minX, maxX);
        int cz = wallZ - rng.nextInt(IceWallConfig.DISASTER_RANGE_AHEAD);
        int cy = world.getHeight(Heightmap.Types.WORLD_SURFACE, cx, cz);
        int snuffed = 0;
        for (int dx = -snuffRadius; dx <= snuffRadius && snuffed < 128; dx++) {
            for (int dz2 = -snuffRadius; dz2 <= snuffRadius && snuffed < 128; dz2++) {
                int x = cx + dx, z = cz + dz2;
                if (!chunkLoaded(world, x, z)) continue;
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos pos = new BlockPos(x, cy + dy, z);
                    Block b = world.getBlockState(pos).getBlock();
                    if (b == Blocks.TORCH || b == Blocks.WALL_TORCH
                            || b == Blocks.SOUL_TORCH || b == Blocks.SOUL_WALL_TORCH) {
                        world.removeBlock(pos, false);
                        snuffed++;
                    } else if (b == Blocks.CAMPFIRE) {
                        world.setBlock(pos, Blocks.CAMPFIRE.defaultBlockState()
                                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT, false),
                                Block.UPDATE_CLIENTS);
                        snuffed++;
                    }
                }
            }
        }
        if (snuffed > 0) {
            announceAll(world, wallZ,
                    Component.literal("FROST SNAP!").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD),
                    Component.literal(snuffed + " light sources extinguished by the cold.").withStyle(ChatFormatting.GRAY),
                    200);
            playSoundAround(world, wallZ, minX, maxX, SoundEvents.FIRE_EXTINGUISH, 2.0f, 1.0f);
        }
    }

    // -----------------------------------------------------------------------
    // 15. Snowdrift tsunami — rapid powder-snow advance far ahead of the wall
    // -----------------------------------------------------------------------

    void triggerSnowdriftTsunami(ServerLevel world, int wallZ, int minX, int maxX) {
        announceAll(world, wallZ,
                Component.literal("SNOWDRIFT TSUNAMI!").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD),
                Component.literal("A massive drift of powder snow surges forward!").withStyle(ChatFormatting.GRAY),
                320);
        for (int i = 0; i < IceWallConfig.SNOW_TSUNAMI_COLUMNS; i++) {
            int x = randomX(minX, maxX);
            int z = wallZ + rng.nextInt(IceWallConfig.SNOW_TSUNAMI_DISTANCE);
            if (!chunkLoaded(world, x, z)) continue;
            int sy = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            BlockPos top = new BlockPos(x, sy, z);
            BlockState below = world.getBlockState(top.below());
            if (below.isSolid()) {
                if (world.getBlockState(top).isAir()) {
                    world.setBlock(top, Blocks.POWDER_SNOW.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
                if (rng.nextBoolean()) {
                    BlockPos above = top.above();
                    if (world.getBlockState(above).isAir())
                        world.setBlock(above, Blocks.SNOW.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        playSoundAround(world, wallZ, minX, maxX, SoundEvents.GENERIC_BIG_FALL, 1.5f, 1.2f);
    }

    // -----------------------------------------------------------------------
    // Utility helpers
    // -----------------------------------------------------------------------

    private int randomX(int minX, int maxX) {
        return minX + rng.nextInt(Math.max(1, maxX - minX));
    }

    private boolean chunkLoaded(ServerLevel world, int x, int z) {
        return world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) != null;
    }

    private void playSoundAround(ServerLevel world, int wallZ, int minX, int maxX,
            net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        int cx = (minX + maxX) / 2;
        int cy = world.getHeight(Heightmap.Types.WORLD_SURFACE, cx, wallZ);
        world.playSound(null, new BlockPos(cx, cy, wallZ), sound, SoundSource.BLOCKS, volume, pitch);
    }

    void announceAll(ServerLevel world, int wallZ,
            Component title, Component subtitle, int range) {
        for (ServerPlayer player : world.players()) {
            double dist = Math.abs(player.getZ() - wallZ);
            if (dist > range) continue;
            player.connection.send(new ClientboundSetTitlesAnimationPacket(6, 40, 14));
            player.connection.send(new ClientboundSetTitleTextPacket(title));
            if (subtitle != null)
                player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        }
    }
}
