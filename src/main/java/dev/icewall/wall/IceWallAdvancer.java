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
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

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
        tickCompassScramble(world, state);
        tickInventoryFrost(world, state);
        tickSupplyDrop(world, state);
        tickSpectatorDrift(world, state);
        tickArmorDrain(world, state);
        tickItemMagnetism(world, state);
        tickSurvivorLeaderboard(world, state);
        railNetwork.tick(world, state);
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
    // Armor frost drain — worn armor loses durability inside hypothermia zone
    // -----------------------------------------------------------------------

    private void tickArmorDrain(ServerLevel world, IceWallState state) {
        if (!state.isActive()) return;
        if (world.getGameTime() % IceWallConfig.ARMOR_DRAIN_INTERVAL_TICKS != 0L) return;
        int wallZ = state.getWallFrontZ();
        for (ServerPlayer player : world.players()) {
            if (player.isSpectator()) continue;
            int dist = player.blockPosition().getZ() - wallZ;
            if (dist <= 0 || dist > IceWallConfig.HYPOTHERMIA_MAX_DISTANCE) continue;
            // Pick a random equipped armor slot to damage
            net.minecraft.world.entity.EquipmentSlot[] armorSlots = {
                net.minecraft.world.entity.EquipmentSlot.HEAD,
                net.minecraft.world.entity.EquipmentSlot.CHEST,
                net.minecraft.world.entity.EquipmentSlot.LEGS,
                net.minecraft.world.entity.EquipmentSlot.FEET
            };
            net.minecraft.world.entity.EquipmentSlot slot =
                    armorSlots[rng.nextInt(armorSlots.length)];
            ItemStack armor = player.getItemBySlot(slot);
            if (armor.isEmpty() || !armor.isDamageableItem()) continue;
            // Scale drain intensity with closeness
            double fraction = 1.0 - (double) dist / IceWallConfig.HYPOTHERMIA_MAX_DISTANCE;
            int damage = (int) Math.max(1, Math.round(IceWallConfig.ARMOR_DRAIN_PER_TICK * fraction));
            armor.hurtAndBreak(damage, player, slot);
        }
    }

    // -----------------------------------------------------------------------
    // Item entity magnetism — dropped items slide toward the wall
    // -----------------------------------------------------------------------

    /**
     * Every ITEM_MAGNET_INTERVAL_TICKS, collect all ItemEntity objects within
     * ITEM_MAGNET_DISTANCE blocks ahead of the wall and add a negative-Z (toward wall)
     * velocity pulse.  Creates the visual spectacle of all loose items sliding
     * into the glacier as if sucked in by a vacuum.
     */
    private void tickItemMagnetism(ServerLevel world, IceWallState state) {
        if (!state.isActive()) return;
        if (world.getGameTime() % IceWallConfig.ITEM_MAGNET_INTERVAL_TICKS != 0L) return;
        int wallZ = state.getWallFrontZ();
        int minX  = state.getMinExploredX();
        int maxX  = state.getMaxExploredX();
        if (minX >= maxX) return;
        AABB zone = new AABB(minX, world.getMinY(), wallZ,
                maxX, world.getMaxY(), wallZ + IceWallConfig.ITEM_MAGNET_DISTANCE);
        for (ItemEntity item : world.getEntitiesOfClass(ItemEntity.class, zone, e -> true)) {
            double dist = item.getZ() - wallZ;
            double fraction = 1.0 - (dist / IceWallConfig.ITEM_MAGNET_DISTANCE);
            double pull = IceWallConfig.ITEM_MAGNET_STRENGTH * fraction;
            item.setDeltaMovement(item.getDeltaMovement().add(0, 0, -pull));
            item.hurtMarked = true;
        }
    }

    // -----------------------------------------------------------------------
    // Survivor leaderboard — periodic chat ranking of players furthest ahead
    // -----------------------------------------------------------------------

    /**
     * Every LEADERBOARD_INTERVAL_TICKS, broadcasts a short ranking in chat
     * showing each online player's distance ahead of the wall (or "consumed"
     * if they're behind it).  Encourages competition among players.
     */
    private void tickSurvivorLeaderboard(ServerLevel world, IceWallState state) {
        if (!state.isActive()) return;
        if (world.getGameTime() % IceWallConfig.LEADERBOARD_INTERVAL_TICKS != 0L) return;
        int wallZ = state.getWallFrontZ();
        java.util.List<ServerPlayer> players = world.players().stream()
                .filter(p -> !p.isSpectator())
                .sorted(java.util.Comparator.comparingInt(p -> -(p.blockPosition().getZ() - wallZ)))
                .toList();
        if (players.isEmpty()) return;
        // Build leaderboard lines
        net.minecraft.network.chat.MutableComponent header = Component.literal("— ❄ Survivor Rankings ❄ —")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
        world.players().forEach(p -> p.sendSystemMessage(header));
        for (int rank = 0; rank < Math.min(players.size(), IceWallConfig.LEADERBOARD_MAX_ENTRIES); rank++) {
            ServerPlayer p = players.get(rank);
            int dist = p.blockPosition().getZ() - wallZ;
            String distStr = dist >= 0
                    ? "+" + dist + " blocks ahead"
                    : "☠ consumed (" + Math.abs(dist) + " blocks behind)";
            ChatFormatting colour = dist >= 200 ? ChatFormatting.GREEN
                    : dist >= 50  ? ChatFormatting.YELLOW
                    : dist >= 0   ? ChatFormatting.RED
                    : ChatFormatting.DARK_RED;
            String medal = rank == 0 ? "🥇 " : rank == 1 ? "🥈 " : rank == 2 ? "🥉 " : (rank + 1) + ". ";
            net.minecraft.network.chat.MutableComponent line = Component.literal(
                    medal + p.getScoreboardName() + " — " + distStr)
                    .withStyle(colour);
            world.players().forEach(viewer -> viewer.sendSystemMessage(line));
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