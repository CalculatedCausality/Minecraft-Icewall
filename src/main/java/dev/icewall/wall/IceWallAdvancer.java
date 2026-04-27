package dev.icewall.wall;

import dev.icewall.config.IceWallConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

public final class IceWallAdvancer {
    private final HeatmapTracker heatmapTracker = new HeatmapTracker();
    private final BlockPlacementQueue placementQueue = new BlockPlacementQueue();
    private final GlacierCorruption corruption = new GlacierCorruption(heatmapTracker);
    private final CaveCorruption caveCorruption = new CaveCorruption(heatmapTracker);
    private final HypothermiaSystem hypothermia = new HypothermiaSystem();
    private final WeatherEffects weatherEffects = new WeatherEffects();
    private final GlacierMobEffects mobEffects = new GlacierMobEffects();
    private final VillagerCommunities villagerCommunities = new VillagerCommunities();
    private final NaturalDisasters naturalDisasters = new NaturalDisasters();
    private final GlacialWhispers whispers = new GlacialWhispers();
    private final GlacierRailNetwork railNetwork = new GlacierRailNetwork();
    private final SurvivorCityGenerator cityGenerator = new SurvivorCityGenerator();
    private final Map<UUID, ServerBossEvent> bossBars = new HashMap<>();
    private final PlayerEffectsTicker effects = new PlayerEffectsTicker();
    private final SupplyDropSystem supplyDrops = new SupplyDropSystem();
    private final Random rng = new Random();

    public void register() {
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk, generated) -> {
            if (isManagedWorld(world)) {
                onChunkLoad(world, chunk);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(this::onEndTick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> removePlayer(handler.player));

        // Respawn safety — if a player spawns inside or behind the glacier, move them to safety
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, isEndConquered) -> {
            if (!(newPlayer.level() instanceof ServerLevel sl)) return;
            if (!isManagedWorld(sl)) return;
            ServerLevel overworld = sl;
            IceWallState state = IceWallState.get(overworld);
            if (!state.isActive()) return;
            int wallZ = state.getWallFrontZ();
            int playerZ = newPlayer.blockPosition().getZ();
            // Player is inside or behind the glacier — teleport to safety
            if (playerZ <= wallZ + IceWallConfig.RESPAWN_SAFE_BUFFER) {
                int safeZ = wallZ + IceWallConfig.RESPAWN_SAFE_BUFFER + 5;
                int surfaceY = overworld.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        newPlayer.blockPosition().getX(), safeZ);
                newPlayer.teleportTo(newPlayer.blockPosition().getX() + 0.5, surfaceY, safeZ + 0.5);
                newPlayer.connection.send(new ClientboundSetActionBarTextPacket(
                        Component.literal("❄ You have been moved away from the glacier zone.")
                                .withStyle(ChatFormatting.AQUA)));
            }
        });
        // Ice toll — 15% chance of freeze damage when mining glacier blocks
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, blockState, blockEntity) -> {
            if (!(world instanceof ServerLevel sl)) return true;
            if (!(player instanceof ServerPlayer sp)) return true;
            if (blockState.getBlock() != Blocks.PACKED_ICE && blockState.getBlock() != Blocks.BLUE_ICE) return true;
            if (rng.nextDouble() < IceWallConfig.ICE_TOLL_CHANCE) {
                sp.hurtServer(sl, sl.damageSources().freeze(), IceWallConfig.ICE_TOLL_DAMAGE);
            }
            return true;
        });
    }

    private void onEndTick(MinecraftServer server) {
        ServerLevel world = server.getLevel(Level.OVERWORLD);
        if (world == null) {
            return;
        }

        IceWallState state = IceWallState.get(world);
        state.initializeIfNeeded(world);
        primeCurrentSlice(world, state);

        if (state.isActive() && state.advanceIfDue()) {
            placementQueue.enqueueLeadingEdge(world, state.getWallFrontZ(), state.getMinExploredX(), state.getMaxExploredX());
            preloadChunksAhead(world, state);
            broadcastGlacierAdvance(world, state);
        }

        placementQueue.process(world);
        corruption.tick(world, state);
        caveCorruption.tick(world, state);
        hypothermia.tick(world, state);
        weatherEffects.tick(world, state);
        mobEffects.tick(world, state);
        villagerCommunities.tick(world, state);
        naturalDisasters.tick(world, state);
        whispers.tick(world, state);
        effects.tick(world, state);
        supplyDrops.tick(world, state);
        railNetwork.tick(world, state);
        cityGenerator.tick(world, state);
        tickConsumedSpawnWarning(world, state);
        updatePlayers(world, state);
    }

    private void onChunkLoad(ServerLevel world, LevelChunk chunk) {
        IceWallState state = IceWallState.get(world);
        state.initializeIfNeeded(world);
        primeCurrentSlice(world, state);
        placementQueue.onChunkLoad(world, chunk.getPos());

        IceWallState.BoundExpansion expansion = state.recordLoadedChunk(chunk.getPos());
        queueExpansion(world, state, expansion.west());
        queueExpansion(world, state, expansion.east());
        cityGenerator.onChunkLoad(world, state, chunk);
    }

    private void queueExpansion(ServerLevel world, IceWallState state, IceWallState.XRange range) {
        if (range == null) {
            return;
        }

        placementQueue.enqueueRange(world, range.minX(), range.maxX(), state.getStartZ(), state.getWallFrontZ());
    }

    private void primeWorld(ServerLevel world) {
        IceWallState state = IceWallState.get(world);
        state.initializeIfNeeded(world);
        primeCurrentSlice(world, state);
    }

    private void primeCurrentSlice(ServerLevel world, IceWallState state) {
        if (!state.consumeBootstrapSlice()) {
            return;
        }

        placementQueue.enqueueLeadingEdge(world, state.getWallFrontZ(), state.getMinExploredX(), state.getMaxExploredX());
        preloadChunksAhead(world, state);
    }

    // Force-load CHUNK_PRELOAD_AHEAD chunk columns ahead of the wall front so block placement
    // never stalls waiting on chunk generation.
    private void preloadChunksAhead(ServerLevel world, IceWallState state) {
        int frontZ = state.getWallFrontZ();
        int minX = state.getMinExploredX();
        int maxX = state.getMaxExploredX();

        int chunkMinX = minX >> 4;
        int chunkMaxX = maxX >> 4;

        for (int chunkX = chunkMinX; chunkX <= chunkMaxX; chunkX++) {
            for (int ahead = 1; ahead <= IceWallConfig.CHUNK_PRELOAD_AHEAD; ahead++) {
                int chunkZ = ((frontZ + (ahead << 4)) >> 4);
                ChunkPos pos = new ChunkPos(chunkX, chunkZ);
                world.getChunkSource().addTicketWithRadius(TicketType.FORCED, pos, 1);
            }
        }
    }

    private void updatePlayers(ServerLevel world, IceWallState state) {
        for (ServerPlayer player : world.players()) {
            if (player.isSpectator()) {
                removePlayer(player);
                continue;
            }

            int distanceAhead = player.blockPosition().getZ() - state.getWallFrontZ();
            if (distanceAhead <= 0) {
                removePlayer(player);
                world.playSound(null, player.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.0F, 0.5F);
                weatherEffects.applyFrostbiteScar(player);
                player.hurtServer(world, world.damageSources().genericKill(), Float.MAX_VALUE);
                continue;
            }

            updateBossBar(player, distanceAhead, state);
            sendWarning(world, player, distanceAhead);
        }
    }

    private void sendWarning(ServerLevel world, ServerPlayer player, int distanceAhead) {
        long gameTime = world.getGameTime();

        if (distanceAhead <= 10) {
            // Flash every second
            if (gameTime % 20L == 0L) {
                sendTitle(player,
                    Component.literal("THE GLACIER IS HERE").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD),
                    Component.literal("You are about to die").withStyle(ChatFormatting.RED),
                    5, 30, 5);
                world.playSound(null, player.blockPosition(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 1.0F, 1.5F);
            }
        } else if (distanceAhead <= 50) {
            if (gameTime % 60L == 0L) {
                sendTitle(player,
                    Component.literal("Run!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                    Component.literal("Glacier in " + distanceAhead + " blocks").withStyle(ChatFormatting.YELLOW),
                    10, 40, 10);
                world.playSound(null, player.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 0.6F, 1.2F);
            }
        } else if (distanceAhead <= 100) {
            if (gameTime % 100L == 0L) {
                sendTitle(player,
                    Component.literal("The ice wall approaches…").withStyle(ChatFormatting.YELLOW),
                    Component.literal(distanceAhead + " blocks away").withStyle(ChatFormatting.WHITE),
                    10, 40, 10);
            }
        }
    }

    private static void sendTitle(ServerPlayer player, Component title, Component subtitle, int fadeIn, int stay, int fadeOut) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
        player.connection.send(new ClientboundSetTitleTextPacket(title));
        player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
    }

    private void updateBossBar(ServerPlayer player, int distanceAhead, IceWallState state) {
        ServerBossEvent bossBar = bossBars.computeIfAbsent(player.getUUID(), ignored -> new ServerBossEvent(
            UUID.randomUUID(),
            Component.literal("Ice wall"),
            BossEvent.BossBarColor.BLUE,
            BossEvent.BossBarOverlay.PROGRESS
        ));

        if (distanceAhead > IceWallConfig.BOSS_BAR_DISTANCE) {
            bossBar.removePlayer(player);
            return;
        }

        float clampedDistance = Math.max(0.0F, Math.min(IceWallConfig.BOSS_BAR_DISTANCE, distanceAhead));
        float progress = 1.0F - (clampedDistance / IceWallConfig.BOSS_BAR_DISTANCE);

        // Colour shifts from blue → yellow → red as player gets closer
        BossEvent.BossBarColor colour;
        if (distanceAhead > 100) {
            colour = BossEvent.BossBarColor.BLUE;
        } else if (distanceAhead > 50) {
            colour = BossEvent.BossBarColor.YELLOW;
        } else {
            colour = BossEvent.BossBarColor.RED;
        }

        long secondsUntilAdvance = (state.getAdvanceIntervalTicks() - state.getTickAccumulator()) / 20L;
        bossBar.setName(Component.literal("Glacier: " + distanceAhead + "m  (advances in " + secondsUntilAdvance + "s)").withStyle(ChatFormatting.AQUA));
        bossBar.setColor(colour);
        bossBar.setProgress(progress);
        bossBar.addPlayer(player);
    }

    private void removePlayer(ServerPlayer player) {
        ServerBossEvent bossBar = bossBars.remove(player.getUUID());
        if (bossBar != null) {
            bossBar.removePlayer(player);
        }
        hypothermia.removeCold(player);
        effects.cleanupPlayer(player);
    }

    private boolean isManagedWorld(ServerLevel world) {
        return world.dimension() == Level.OVERWORLD;
    }

    /** Start spectator drift mode. Delegates to {@link PlayerEffectsTicker}. */
    public void startSpectate(ServerPlayer player) {
        effects.startSpectate(player);
    }

    /** Remove the player from spectator drift mode. Delegates to {@link PlayerEffectsTicker}. */
    public boolean stopSpectate(ServerPlayer player) {
        return effects.stopSpectate(player);
    }

    /** Expose the heatmap tracker for use by the /icewall heatmap command. */
    public HeatmapTracker getHeatmapTracker() {
        return heatmapTracker;
    }

    // -----------------------------------------------------------------------
    // Glacier advance event — boom + subtitle broadcast when the wall steps
    // -----------------------------------------------------------------------

    private static final String[] ADVANCE_LINES = {
        "The glacier advances.",
        "Another metre consumed.",
        "The ice presses forward.",
        "There is no stopping it.",
        "It grows closer.",
    };

    private void broadcastGlacierAdvance(ServerLevel world, IceWallState state) {
        int wallZ = state.getWallFrontZ();
        // Push world spawn to stay safely ahead of the glacier
        pushWorldSpawn(world, state);
        String line = ADVANCE_LINES[(int) (world.getGameTime() / state.getAdvanceIntervalTicks()
                % ADVANCE_LINES.length)];
        Component subtitle = Component.literal("❄ " + line)
                .withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.ITALIC);
        for (ServerPlayer player : world.players()) {
            if (player.isSpectator()) continue;
            int dist = player.blockPosition().getZ() - wallZ;
            // --- Global distant rumble (all living players, very quiet) ---
            world.playSound(null, player.blockPosition(),
                    SoundEvents.RAVAGER_STEP, SoundSource.AMBIENT, 0.18f, 0.18f);
            if (dist <= 0 || dist > IceWallConfig.BLIZZARD_LOCK_DISTANCE) continue;
            // Deep concussive boom — pitch drops as player gets closer
            float pitch = 0.35f + (dist / (float) IceWallConfig.BLIZZARD_LOCK_DISTANCE) * 0.25f;
            world.playSound(null, player.blockPosition(),
                    SoundEvents.RAVAGER_STEP, SoundSource.AMBIENT, 1.6f, pitch);
            // Subtitle (blank title so it doesn’t obscure screen)
            player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 50, 15));
            player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("")));
            player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        }
    }

    // -----------------------------------------------------------------------
    // Respawn safety systems
    // -----------------------------------------------------------------------

    /**
     * On each glacier advance, update the world-level respawn position to stay
     * RESPAWN_SAFE_BUFFER blocks ahead of the wall.  This is the fallback spawn
     * used by players who have no bed or respawn anchor, and by new players.
     */
    private void pushWorldSpawn(ServerLevel world, IceWallState state) {
        int wallZ = state.getWallFrontZ();
        int safeZ = wallZ + IceWallConfig.RESPAWN_SAFE_BUFFER;
        // Use the world-centre X as the anchor
        int centreX = (state.getMinExploredX() + state.getMaxExploredX()) / 2;
        int surfaceY = world.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                centreX, safeZ);
        BlockPos safePos = new BlockPos(centreX, surfaceY, safeZ);
        world.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, safePos, 0.0f, 0.0f));
    }

    /**
     * Every RESPAWN_SCAN_INTERVAL_TICKS, checks each player's individual respawn
     * config (bed or respawn anchor).  If their set spawn is now behind or at the
     * glacier face, clear it and notify them so they use the world spawn instead.
     */
    private void tickConsumedSpawnWarning(ServerLevel world, IceWallState state) {
        if (!state.isActive()) return;
        if (world.getGameTime() % IceWallConfig.RESPAWN_SCAN_INTERVAL_TICKS != 0L) return;
        int wallZ = state.getWallFrontZ();
        for (ServerPlayer player : world.players()) {
            if (player.isSpectator()) continue;
            var config = player.getRespawnConfig();
            if (config == null) continue;
            var data = config.respawnData();
            // Only concern ourselves with Overworld spawns
            if (!Level.OVERWORLD.equals(data.dimension())) continue;
            int spawnZ = data.pos().getZ();
            if (spawnZ <= wallZ + IceWallConfig.RESPAWN_SAFE_BUFFER) {
                // Clear the individual spawn — player falls back to world spawn
                player.setRespawnPosition(null, false);
                player.connection.send(new ClientboundSetActionBarTextPacket(
                        Component.literal("⚠ Your respawn point was consumed by the glacier!")
                                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
            }
        }
    }
}