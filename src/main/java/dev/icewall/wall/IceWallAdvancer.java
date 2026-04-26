package dev.icewall.wall;

import dev.icewall.config.IceWallConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
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
    private final Map<UUID, ServerBossEvent> bossBars = new HashMap<>();
    private final Map<UUID, Double> spectatorDriftX = new HashMap<>();
    private final Map<UUID, Map<Integer, FrostEntry>> frostEntries = new HashMap<>();
    private final Random rng = new Random();
    private long lastSupplyDropTick = 0L;

    private record FrostEntry(ItemStack original, long expiryTick) {}

    public void register() {
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk, generated) -> {
            if (isManagedWorld(world)) {
                onChunkLoad(world, chunk);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(this::onEndTick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> removePlayer(handler.player));
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
        }

        placementQueue.process(world);
        corruption.tick(world, state);
        caveCorruption.tick(world, state);
        hypothermia.tick(world, state);
        weatherEffects.tick(world, state);
        mobEffects.tick(world, state);
        villagerCommunities.tick(world, state);
        naturalDisasters.tick(world, state);
        tickCompassScramble(world, state);
        tickInventoryFrost(world, state);
        tickSupplyDrop(world, state);
        tickSpectatorDrift(world, state);
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
        spectatorDriftX.remove(player.getUUID());
        Map<Integer, FrostEntry> entries = frostEntries.remove(player.getUUID());
        if (entries != null) {
            entries.forEach((slot, entry) -> player.getInventory().setItem(slot, entry.original()));
        }
    }

    private boolean isManagedWorld(ServerLevel world) {
        return world.dimension() == Level.OVERWORLD;
    }

    // -----------------------------------------------------------------------
    // Compass scramble — disorientation actionbar message
    // -----------------------------------------------------------------------

    private static final String[] SCRAMBLE_ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖", "?", "⊕", "×"};

    private void tickCompassScramble(ServerLevel world, IceWallState state) {
        if (!state.isActive()) return;
        if (world.getGameTime() % IceWallConfig.COMPASS_SCRAMBLE_INTERVAL_TICKS != 0L) return;
        int wallZ = state.getWallFrontZ();
        for (ServerPlayer player : world.players()) {
            if (player.isSpectator()) continue;
            int dist = player.blockPosition().getZ() - wallZ;
            if (dist <= 0 || dist > IceWallConfig.COMPASS_SCRAMBLE_DISTANCE) continue;
            String arrow = SCRAMBLE_ARROWS[rng.nextInt(SCRAMBLE_ARROWS.length)];
            player.connection.send(new ClientboundSetActionBarTextPacket(
                Component.literal("\u26a7 Magnetic anomaly: " + arrow + " " + arrow + " " + arrow)
                    .withStyle(ChatFormatting.DARK_AQUA)));
        }
    }

    // -----------------------------------------------------------------------
    // Inventory frost — temporarily renames a random hotbar item
    // -----------------------------------------------------------------------

    private void tickInventoryFrost(ServerLevel world, IceWallState state) {
        long gameTime = world.getGameTime();
        // Restore expired frosted items
        for (ServerPlayer player : world.players()) {
            Map<Integer, FrostEntry> entries = frostEntries.get(player.getUUID());
            if (entries == null) continue;
            entries.entrySet().removeIf(e -> {
                if (gameTime < e.getValue().expiryTick()) return false;
                player.getInventory().setItem(e.getKey(), e.getValue().original());
                player.inventoryMenu.broadcastChanges();
                return true;
            });
            if (entries.isEmpty()) frostEntries.remove(player.getUUID());
        }
        // Apply new frost entries
        if (!state.isActive()) return;
        if (gameTime % IceWallConfig.INVENTORY_FROST_INTERVAL_TICKS != 0L) return;
        int wallZ = state.getWallFrontZ();
        for (ServerPlayer player : world.players()) {
            if (player.isSpectator()) continue;
            int dist = player.blockPosition().getZ() - wallZ;
            if (dist <= 0 || dist > IceWallConfig.INVENTORY_FROST_DISTANCE) continue;
            int slot = rng.nextInt(9);
            ItemStack item = player.getInventory().getItem(slot);
            if (item.isEmpty()) continue;
            Map<Integer, FrostEntry> entries = frostEntries.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
            if (entries.containsKey(slot)) continue;
            ItemStack frosted = item.copy();
            frosted.set(DataComponents.CUSTOM_NAME,
                Component.literal("\u2744 ").append(item.getHoverName()).append(" \u2744")
                    .withStyle(ChatFormatting.AQUA));
            player.getInventory().setItem(slot, frosted);
            player.inventoryMenu.broadcastChanges();
            entries.put(slot, new FrostEntry(item.copy(), gameTime + IceWallConfig.INVENTORY_FROST_DURATION_TICKS));
        }
    }

    // -----------------------------------------------------------------------
    // Survivor supply drop — item package for the furthest-ahead player
    // -----------------------------------------------------------------------

    private void tickSupplyDrop(ServerLevel world, IceWallState state) {
        if (!state.isActive()) return;
        long gameTime = world.getGameTime();
        if (gameTime - lastSupplyDropTick < IceWallConfig.SUPPLY_DROP_INTERVAL_TICKS) return;
        ServerPlayer target = null;
        int maxDist = 0;
        for (ServerPlayer p : world.players()) {
            if (p.isSpectator()) continue;
            int dist = p.blockPosition().getZ() - state.getWallFrontZ();
            if (dist > maxDist) { maxDist = dist; target = p; }
        }
        if (target == null) return;
        lastSupplyDropTick = gameTime;
        spawnSupplyDrop(world, target);
    }

    private void spawnSupplyDrop(ServerLevel world, ServerPlayer target) {
        double x = target.getX();
        double y = target.getY() + 1;
        double z = target.getZ();
        Component msg = Component.literal("\u2605 Supply drop for "
            + target.getDisplayName().getString()
            + " at " + target.blockPosition()).withStyle(ChatFormatting.GOLD);
        world.players().forEach(p -> p.sendSystemMessage(msg));
        world.playSound(null, target.blockPosition(),
            SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 2.0F);
        dropItem(world, x, y, z, Items.IRON_SWORD, 1);
        dropItem(world, x, y, z, Items.GOLDEN_APPLE, 3);
        dropItem(world, x, y, z, Items.COOKED_BEEF, 16);
        dropItem(world, x, y, z, Items.TORCH, 32);
        dropItem(world, x, y, z, Items.WHITE_WOOL, 8);
        dropItem(world, x, y, z, Items.LEATHER_BOOTS, 1);
    }

    private static void dropItem(ServerLevel world, double x, double y, double z,
                                  net.minecraft.world.item.Item item, int count) {
        ItemEntity entity = new ItemEntity(world, x, y, z, new ItemStack(item, count));
        entity.setPickUpDelay(20);
        world.addFreshEntity(entity);
    }

    // -----------------------------------------------------------------------
    // Spectator drift cam — drifts east along the wall face
    // -----------------------------------------------------------------------

    /** Start spectator drift mode. The player is set to spectator and their X
     *  position is drifted east each tick at {@code SPECTATOR_DRIFT_SPEED}. */
    public void startSpectate(ServerPlayer player) {
        player.setGameMode(GameType.SPECTATOR);
        spectatorDriftX.put(player.getUUID(), (double) player.blockPosition().getX());
    }

    /** Remove the player from spectator drift mode (does not restore game mode). */
    public boolean stopSpectate(ServerPlayer player) {
        return spectatorDriftX.remove(player.getUUID()) != null;
    }

    private void tickSpectatorDrift(ServerLevel world, IceWallState state) {
        spectatorDriftX.entrySet().removeIf(entry -> {
            ServerPlayer player = world.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || !player.isSpectator()) return true;
            double newX = entry.getValue() + IceWallConfig.SPECTATOR_DRIFT_SPEED;
            entry.setValue(newX);
            double driftZ = state.getWallFrontZ() - 5.0;
            double y = Math.max(64.0, player.getY());
            player.teleportTo(newX, y, driftZ);
            if (newX > state.getMaxExploredX()) {
                entry.setValue((double) state.getMinExploredX());
            }
            return false;
        });
    }

    /** Expose the heatmap tracker for use by the /icewall heatmap command. */
    public HeatmapTracker getHeatmapTracker() {
        return heatmapTracker;
    }
}