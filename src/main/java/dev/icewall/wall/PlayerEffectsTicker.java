package dev.icewall.wall;

import dev.icewall.config.IceWallConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;

/**
 * All player-proximity effects that fire on every server tick.
 *
 * Extracted from {@link IceWallAdvancer} to keep that class focused on
 * orchestration.  Stateful data (spectator drift positions, frosted inventory
 * entries) lives here.
 */
public final class PlayerEffectsTicker {

    private record FrostEntry(ItemStack original, long expiryTick) {}

    private final Map<UUID, Double>              spectatorDriftX = new HashMap<>();
    private final Map<UUID, Map<Integer, FrostEntry>> frostEntries  = new HashMap<>();
    private final Random rng = new Random();

    private static final String[] SCRAMBLE_ARROWS =
            {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖", "?", "⊕", "×"};

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Runs all player effect sub-tickers for one server tick. */
    public void tick(ServerLevel world, IceWallState state) {
        tickCompassScramble(world, state);
        tickInventoryFrost(world, state);
        tickArmorDrain(world, state);
        tickItemMagnetism(world, state);
        tickSpectatorDrift(world, state);
        tickSurvivorLeaderboard(world, state);
    }

    /** Start spectator drift mode for {@code player}. */
    public void startSpectate(ServerPlayer player) {
        player.setGameMode(GameType.SPECTATOR);
        spectatorDriftX.put(player.getUUID(), (double) player.blockPosition().getX());
    }

    /** Remove the player from spectator drift mode (does not restore game mode). */
    public boolean stopSpectate(ServerPlayer player) {
        return spectatorDriftX.remove(player.getUUID()) != null;
    }

    /**
     * Clean up all per-player state when a player disconnects or is killed.
     * Restores any frosted inventory items and removes drift tracking.
     */
    public void cleanupPlayer(ServerPlayer player) {
        spectatorDriftX.remove(player.getUUID());
        Map<Integer, FrostEntry> entries = frostEntries.remove(player.getUUID());
        if (entries != null) {
            entries.forEach((slot, entry) -> player.getInventory().setItem(slot, entry.original()));
        }
    }

    // -----------------------------------------------------------------------
    // Compass scramble — disorientation actionbar message
    // -----------------------------------------------------------------------

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
            EquipmentSlot[] armorSlots = {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET
            };
            EquipmentSlot slot = armorSlots[rng.nextInt(armorSlots.length)];
            ItemStack armor = player.getItemBySlot(slot);
            if (armor.isEmpty() || !armor.isDamageableItem()) continue;
            double fraction = 1.0 - (double) dist / IceWallConfig.HYPOTHERMIA_MAX_DISTANCE;
            int damage = (int) Math.max(1, Math.round(IceWallConfig.ARMOR_DRAIN_PER_TICK * fraction));
            armor.hurtAndBreak(damage, player, slot);
        }
    }

    // -----------------------------------------------------------------------
    // Item entity magnetism — dropped items slide toward the wall
    // -----------------------------------------------------------------------

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
    // Spectator drift cam — drifts east along the wall face
    // -----------------------------------------------------------------------

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

    // -----------------------------------------------------------------------
    // Survivor leaderboard — periodic chat ranking of players furthest ahead
    // -----------------------------------------------------------------------

    private void tickSurvivorLeaderboard(ServerLevel world, IceWallState state) {
        if (!state.isActive()) return;
        if (world.getGameTime() % IceWallConfig.LEADERBOARD_INTERVAL_TICKS != 0L) return;
        int wallZ = state.getWallFrontZ();
        java.util.List<ServerPlayer> players = world.players().stream()
                .filter(p -> !p.isSpectator())
                .sorted(java.util.Comparator.comparingInt(p -> -(p.blockPosition().getZ() - wallZ)))
                .toList();
        if (players.isEmpty()) return;
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
}
