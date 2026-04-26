package dev.icewall.wall;

import dev.icewall.config.IceWallConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

public final class IceWallAdvancer {
    private final BlockPlacementQueue placementQueue = new BlockPlacementQueue();
    private final Map<UUID, ServerBossEvent> bossBars = new HashMap<>();

    public void register() {
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk, generated) -> {
            if (isManagedWorld(world)) {
                onChunkLoad(world, chunk);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(this::onEndTick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> removePlayer(handler.player));
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
        }

        placementQueue.process(world);
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
    }

    private boolean isManagedWorld(ServerLevel world) {
        return world.dimension() == Level.OVERWORLD;
    }
}