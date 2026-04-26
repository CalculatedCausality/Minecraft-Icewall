package dev.icewall.wall;

import dev.icewall.config.IceWallConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Gradually reduces a player's maximum health the closer they are to the glacier.
 *
 * Cold level (0–5) maps to a transient {@link AttributeModifier} on MAX_HEALTH:
 *   level 1 → -1 HP  (−½ heart)
 *   level 5 → -5 HP  (−2½ hearts)
 *
 * Cold accumulates as the player approaches and decays by 1 level per check
 * interval when they move away.  On death or disconnect, call {@link #removeCold}
 * to ensure max health is fully restored.
 */
public final class HypothermiaSystem {

    private static final Identifier MODIFIER_ID =
            Identifier.fromNamespaceAndPath("icewall", "hypothermia");
    private static final double HP_PENALTY_PER_LEVEL = -1.0;
    private static final int MAX_COLD_LEVEL = 5;
    private static final int TICK_INTERVAL = 100;

    private final Map<UUID, Integer> coldLevels = new HashMap<>();

    public void tick(ServerLevel world, IceWallState state) {
        if (!state.isActive()) {
            return;
        }
        if (world.getGameTime() % TICK_INTERVAL != 0L) {
            return;
        }

        int wallZ = state.getWallFrontZ();
        for (ServerPlayer player : world.players()) {
            if (player.isSpectator()) {
                removeCold(player);
                continue;
            }
            int dist = player.blockPosition().getZ() - wallZ;
            if (dist <= 0 || dist > IceWallConfig.HYPOTHERMIA_MAX_DISTANCE) {
                decayCold(player);
                continue;
            }
            double fraction = 1.0 - (double) dist / IceWallConfig.HYPOTHERMIA_MAX_DISTANCE;
            int target = (int) Math.ceil(fraction * MAX_COLD_LEVEL);
            int current = coldLevels.getOrDefault(player.getUUID(), 0);
            if (target > current) {
                applyCold(player, Math.min(target, MAX_COLD_LEVEL));
            } else if (target < current) {
                applyCold(player, Math.max(0, current - 1));
            }
        }
    }

    private void applyCold(ServerPlayer player, int level) {
        coldLevels.put(player.getUUID(), level);
        AttributeInstance attr = player.getAttribute(Attributes.MAX_HEALTH);
        if (attr == null) {
            return;
        }
        attr.removeModifier(MODIFIER_ID);
        if (level > 0) {
            attr.addOrUpdateTransientModifier(new AttributeModifier(
                    MODIFIER_ID, HP_PENALTY_PER_LEVEL * level,
                    AttributeModifier.Operation.ADD_VALUE));
            float max = (float) Math.max(1.0, attr.getValue());
            if (player.getHealth() > max) {
                player.setHealth(max);
            }
        }
    }

    private void decayCold(ServerPlayer player) {
        int current = coldLevels.getOrDefault(player.getUUID(), 0);
        if (current > 0) {
            applyCold(player, current - 1);
        }
        if (current <= 1) {
            coldLevels.remove(player.getUUID());
        }
    }

    public void removeCold(ServerPlayer player) {
        if (coldLevels.remove(player.getUUID()) == null) {
            return;
        }
        AttributeInstance attr = player.getAttribute(Attributes.MAX_HEALTH);
        if (attr != null) {
            attr.removeModifier(MODIFIER_ID);
        }
    }
}
