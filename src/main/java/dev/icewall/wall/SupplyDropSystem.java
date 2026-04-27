package dev.icewall.wall;

import dev.icewall.config.IceWallConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Periodic supply-drop system — once every {@code SUPPLY_DROP_INTERVAL_TICKS},
 * the player who is furthest ahead of the glacier receives a package of useful
 * items spawned at their location.
 *
 * Extracted from {@link IceWallAdvancer} to keep that class focused on
 * orchestration.
 */
public final class SupplyDropSystem {

    private long lastSupplyDropTick = 0L;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Called once per server tick from {@link IceWallAdvancer#onEndTick}. */
    public void tick(ServerLevel world, IceWallState state) {
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

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

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
        dropItem(world, x, y, z, Items.IRON_SWORD,    1);
        dropItem(world, x, y, z, Items.GOLDEN_APPLE,  3);
        dropItem(world, x, y, z, Items.COOKED_BEEF,  16);
        dropItem(world, x, y, z, Items.TORCH,         32);
        dropItem(world, x, y, z, Items.WHITE_WOOL,     8);
        dropItem(world, x, y, z, Items.LEATHER_BOOTS,  1);
    }

    private static void dropItem(ServerLevel world, double x, double y, double z,
                                  Item item, int count) {
        ItemEntity entity = new ItemEntity(world, x, y, z, new ItemStack(item, count));
        entity.setPickUpDelay(20);
        world.addFreshEntity(entity);
    }
}
