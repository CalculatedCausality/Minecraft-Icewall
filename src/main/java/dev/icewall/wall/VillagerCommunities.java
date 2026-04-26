package dev.icewall.wall;

import dev.icewall.config.IceWallConfig;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * Villager community caravan system.
 *
 * Periodically scans the world ahead of the glacier for clusters of villagers.
 * Each cluster is tracked as a Community.  When the wall reaches within
 * VILLAGE_EVACUATION_DISTANCE blocks of a community, it triggers an evacuation:
 *
 *  - All nearby players see a title announcement.
 *  - A bell rings at the community centre.
 *  - Scattered trade goods are dropped (the villagers "packed in a hurry").
 *  - Every 5 ticks, living members receive Speed II and a southward velocity nudge.
 *  - If they reach VILLAGE_SAFE_DISTANCE south of the wall, they celebrate.
 *  - If the wall overtakes them, the community is consumed and players are notified.
 */
public final class VillagerCommunities {

    // -----------------------------------------------------------------------
    // Internal state
    // -----------------------------------------------------------------------

    private enum CommunityState { SETTLED, EVACUATING, FLED, CONSUMED }

    private static final class Community {
        final List<UUID> members;
        int centerX;
        int centerZ;
        CommunityState state = CommunityState.SETTLED;
        boolean droppedSupplies = false;

        Community(List<UUID> members, int centerX, int centerZ) {
            this.members = new ArrayList<>(members);
            this.centerX = centerX;
            this.centerZ = centerZ;
        }
    }

    private final List<Community> communities = new ArrayList<>();
    private final Random rng = new Random();

    // -----------------------------------------------------------------------
    // Public tick entry point
    // -----------------------------------------------------------------------

    public void tick(ServerLevel world, IceWallState state) {
        if (!state.isActive()) return;

        int wallZ = state.getWallFrontZ();

        // Periodic community scan
        if (world.getGameTime() % IceWallConfig.VILLAGE_SCAN_INTERVAL_TICKS == 0L) {
            scanForCommunities(world, state);
        }

        // Remove finished communities first, then tick the rest
        communities.removeIf(c -> c.state == CommunityState.FLED
                || c.state == CommunityState.CONSUMED);

        for (Community community : new ArrayList<>(communities)) {
            tickCommunity(world, community, wallZ);
        }
    }

    // -----------------------------------------------------------------------
    // Community detection
    // -----------------------------------------------------------------------

    private void scanForCommunities(ServerLevel world, IceWallState state) {
        int wallZ = state.getWallFrontZ();
        int minX  = state.getMinExploredX();
        int maxX  = state.getMaxExploredX();

        // Scan the strip ahead of the wall
        AABB scanBox = new AABB(
                minX, world.getMinY(), wallZ - IceWallConfig.VILLAGE_SCAN_DISTANCE,
                maxX, world.getMaxY(), wallZ + 32);

        List<Villager> allVillagers = world.getEntitiesOfClass(
                Villager.class, scanBox, v -> !v.isRemoved());
        if (allVillagers.isEmpty()) return;

        // Collect UUIDs already assigned to an existing community
        Set<UUID> tracked = new HashSet<>();
        for (Community c : communities) {
            tracked.addAll(c.members);
        }

        // Untracked villagers eligible for clustering
        List<Villager> pool = new ArrayList<>();
        for (Villager v : allVillagers) {
            if (!tracked.contains(v.getUUID())) {
                pool.add(v);
            }
        }
        if (pool.isEmpty()) return;

        // BFS flood-fill clustering: villagers within VILLAGE_CLUSTER_RADIUS
        // of *any* existing cluster member are pulled in (not just the seed).
        boolean[] assigned = new boolean[pool.size()];
        for (int i = 0; i < pool.size(); i++) {
            if (assigned[i]) continue;

            // Expand cluster from this seed
            Set<Integer> clusterIndices = new HashSet<>();
            Queue<Integer> frontier = new LinkedList<>();
            clusterIndices.add(i);
            frontier.add(i);
            assigned[i] = true;

            while (!frontier.isEmpty()) {
                int curr = frontier.poll();
                Villager currV = pool.get(curr);
                for (int j = 0; j < pool.size(); j++) {
                    if (assigned[j]) continue;
                    Villager other = pool.get(j);
                    double dx = other.getX() - currV.getX();
                    double dz = other.getZ() - currV.getZ();
                    if (Math.sqrt(dx * dx + dz * dz) <= IceWallConfig.VILLAGE_CLUSTER_RADIUS) {
                        assigned[j] = true;
                        clusterIndices.add(j);
                        frontier.add(j);
                    }
                }
            }

            if (clusterIndices.size() < IceWallConfig.VILLAGE_MIN_SIZE) continue;

            // Compute centroid and build UUID list
            double sumX = 0, sumZ = 0;
            List<UUID> uuids = new ArrayList<>();
            for (int idx : clusterIndices) {
                Villager v = pool.get(idx);
                uuids.add(v.getUUID());
                sumX += v.getX();
                sumZ += v.getZ();
            }
            int cx = (int) (sumX / clusterIndices.size());
            int cz = (int) (sumZ / clusterIndices.size());

            Community community = new Community(uuids, cx, cz);
            communities.add(community);

            // Subtle hint to nearby players that a settlement was noticed
            announceNearby(world, cx, cz,
                    Component.literal("Villagers murmur with unease as the horizon turns white...")
                            .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC),
                    null, 192);
        }
    }

    // -----------------------------------------------------------------------
    // Community lifecycle
    // -----------------------------------------------------------------------

    private void tickCommunity(ServerLevel world, Community community, int wallZ) {
        int distFromWall = community.centerZ - wallZ; // positive = ahead of wall

        if (community.state == CommunityState.SETTLED
                && distFromWall <= IceWallConfig.VILLAGE_EVACUATION_DISTANCE) {
            startEvacuation(world, community, wallZ);
        }

        if (community.state == CommunityState.EVACUATING) {
            // Wall has overtaken the community centre
            if (distFromWall <= -IceWallConfig.VILLAGE_CLUSTER_RADIUS) {
                consumeCommunity(world, community);
                return;
            }
            tickEvacuation(world, community, wallZ);
        }
    }

    // -----------------------------------------------------------------------
    // Evacuation start
    // -----------------------------------------------------------------------

    private void startEvacuation(ServerLevel world, Community community, int wallZ) {
        community.state = CommunityState.EVACUATING;

        // Loud announcement to nearby players
        announceNearby(world, community.centerX, community.centerZ,
                Component.literal("VILLAGERS EVACUATING!")
                        .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD),
                Component.literal(community.members.size() + " villager"
                        + (community.members.size() != 1 ? "s are" : " is")
                        + " fleeing the glacier!")
                        .withStyle(ChatFormatting.WHITE),
                288);

        // Ring a bell at the community centre
        int surfaceY = world.getHeight(
                Heightmap.Types.WORLD_SURFACE, community.centerX, community.centerZ);
        BlockPos centre = new BlockPos(community.centerX, surfaceY, community.centerZ);
        world.playSound(null, centre, SoundEvents.BELL_BLOCK,
                SoundSource.NEUTRAL, 3.0F, 0.8F);

        // Villagers shout "No!" in panic
        world.playSound(null, centre, SoundEvents.VILLAGER_NO,
                SoundSource.NEUTRAL, 2.0F, 0.6F + rng.nextFloat() * 0.3F);
    }

    // -----------------------------------------------------------------------
    // Evacuation tick (runs every game tick while EVACUATING)
    // -----------------------------------------------------------------------

    private void tickEvacuation(ServerLevel world, Community community, int wallZ) {
        long gameTime = world.getGameTime();

        // Drop scattered trade goods once, right as the caravan sets off
        if (!community.droppedSupplies) {
            community.droppedSupplies = true;
            dropAbandonedGoods(world, community);
        }

        // Nudge and buff living members every 5 ticks
        if (gameTime % 5L != 0L) return;

        List<UUID> dead = new ArrayList<>();
        int surviving = 0;
        double sumX = 0, sumZ = 0;

        for (UUID uid : community.members) {
            net.minecraft.world.entity.Entity e = world.getEntityInAnyDimension(uid);
            if (!(e instanceof Villager villager) || villager.isRemoved()) {
                dead.add(uid);
                continue;
            }
            surviving++;
            sumX += villager.getX();
            sumZ += villager.getZ();

            // Speed II while fleeing
            villager.addEffect(new MobEffectInstance(
                    MobEffects.SPEED, 60, 1, false, false));

            // Southward velocity nudge; panic-sprint if the wall is very close
            double nudge = IceWallConfig.VILLAGE_FLEE_NUDGE;
            int personalDist = (int) villager.getZ() - wallZ;
            if (personalDist < 16) nudge *= 2.0;

            villager.setDeltaMovement(
                    villager.getDeltaMovement().x() * 0.6,
                    villager.getDeltaMovement().y(),
                    Math.max(villager.getDeltaMovement().z(), nudge));
        }

        community.members.removeAll(dead);

        // Keep centre tracking current
        if (surviving > 0) {
            community.centerX = (int) (sumX / surviving);
            community.centerZ = (int) (sumZ / surviving);
        }

        // Community reached safety
        int safeZ = wallZ + IceWallConfig.VILLAGE_SAFE_DISTANCE;
        if (community.members.isEmpty() || community.centerZ > safeZ) {
            community.state = CommunityState.FLED;
            if (surviving > 0) {
                announceNearby(world, community.centerX, community.centerZ,
                        Component.literal(surviving + " villager"
                                + (surviving != 1 ? "s have" : " has")
                                + " escaped the glacier!")
                                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                        Component.literal("They will rebuild further south.")
                                .withStyle(ChatFormatting.GREEN),
                        320);
                int sy = world.getHeight(
                        Heightmap.Types.WORLD_SURFACE, community.centerX, community.centerZ);
                world.playSound(null,
                        new BlockPos(community.centerX, sy, community.centerZ),
                        SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL,
                        2.0F, 0.9F + rng.nextFloat() * 0.2F);
            }
            return;
        }

        // Periodic panic cry while the caravan is moving
        if (gameTime % 40L == 0L && surviving > 0) {
            int sy = world.getHeight(
                    Heightmap.Types.WORLD_SURFACE, community.centerX, community.centerZ);
            world.playSound(null,
                    new BlockPos(community.centerX, sy, community.centerZ),
                    SoundEvents.VILLAGER_HURT, SoundSource.NEUTRAL,
                    1.5F, 0.7F + rng.nextFloat() * 0.4F);
        }
    }

    // -----------------------------------------------------------------------
    // Wall consumed the community
    // -----------------------------------------------------------------------

    private void consumeCommunity(ServerLevel world, Community community) {
        community.state = CommunityState.CONSUMED;

        // Count the stragglers
        int frozen = 0;
        for (UUID uid : community.members) {
            net.minecraft.world.entity.Entity e = world.getEntityInAnyDimension(uid);
            if (e instanceof Villager v && !v.isRemoved()) frozen++;
        }

        int total = community.members.size();
        announceNearby(world, community.centerX, community.centerZ,
                Component.literal("The glacier has consumed the village!")
                        .withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.BOLD),
                Component.literal((frozen > 0 ? frozen : total) + " villager"
                        + ((frozen > 0 ? frozen : total) != 1 ? "s were" : " was")
                        + " swallowed by the ice.")
                        .withStyle(ChatFormatting.GRAY),
                384);
    }

    // -----------------------------------------------------------------------
    // Scattered goods — trade items left behind as the caravan fled in a hurry
    // -----------------------------------------------------------------------

    private void dropAbandonedGoods(ServerLevel world, Community community) {
        int x = community.centerX;
        int z = community.centerZ;
        int y = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);

        ItemStack[] goods = {
            new ItemStack(Items.BREAD, 3 + rng.nextInt(6)),
            new ItemStack(Items.EMERALD, 1 + rng.nextInt(5)),
            new ItemStack(Items.WHEAT, 6 + rng.nextInt(12)),
            new ItemStack(Items.BOOK, 1),
            new ItemStack(Items.LEATHER, 2 + rng.nextInt(4)),
        };

        for (ItemStack stack : goods) {
            double ox = (rng.nextDouble() - 0.5) * 6;
            double oz = (rng.nextDouble() - 0.5) * 6;
            ItemEntity item = new ItemEntity(world, x + ox, y + 0.5, z + oz, stack);
            item.setPickUpDelay(20);
            world.addFreshEntity(item);
        }
    }

    // -----------------------------------------------------------------------
    // Helper — send a title screen to players within range of a world position
    // -----------------------------------------------------------------------

    private void announceNearby(ServerLevel world, int x, int z,
            Component title, Component subtitle, int range) {
        for (ServerPlayer player : world.players()) {
            double dx = player.getX() - x;
            double dz = player.getZ() - z;
            if (Math.sqrt(dx * dx + dz * dz) > range) continue;

            player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 50, 20));
            player.connection.send(new ClientboundSetTitleTextPacket(title));
            if (subtitle != null) {
                player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
            }
        }
    }
}
