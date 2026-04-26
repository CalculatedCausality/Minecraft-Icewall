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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
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

    private enum CommunityState { SETTLED, EVACUATING, REBUILDING, FLED, CONSUMED }

    private record PlacedBlock(BlockPos pos, BlockState state) {}

    private static final class Community {
        final List<UUID> members;
        int centerX;
        int centerZ;
        CommunityState state = CommunityState.SETTLED;
        boolean droppedSupplies = false;
        // Rebuild phase
        int rebuildX, rebuildZ;
        List<PlacedBlock> buildQueue = null;
        boolean rebuildComplete = false;

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

        if (community.state == CommunityState.REBUILDING) {
            tickRebuilding(world, community);
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

        // Community reached safety — begin reconstruction
        int safeZ = wallZ + IceWallConfig.VILLAGE_SAFE_DISTANCE;
        if (community.members.isEmpty() || community.centerZ > safeZ) {
            if (surviving > 0) {
                community.rebuildX = community.centerX;
                community.rebuildZ = community.centerZ + 24;
                community.buildQueue = generateBuildPlan(
                        world, community.rebuildX, community.rebuildZ, surviving);
                community.state = CommunityState.REBUILDING;
                relocateVillagersToSite(world, community);
                announceNearby(world, community.centerX, community.centerZ,
                        Component.literal(surviving + " villager"
                                + (surviving != 1 ? "s have" : " has")
                                + " escaped! They begin to rebuild.")
                                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                        Component.literal("Watch the new settlement rise to the south...")
                                .withStyle(ChatFormatting.GREEN),
                        320);
            } else {
                community.state = CommunityState.FLED;
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
    // -----------------------------------------------------------------------
    // Village reconstruction
    // -----------------------------------------------------------------------

    private void tickRebuilding(ServerLevel world, Community community) {
        if (community.buildQueue == null || community.buildQueue.isEmpty()) {
            if (!community.rebuildComplete) {
                community.rebuildComplete = true;
                community.state = CommunityState.FLED; // triggers removal from list
                int sy = world.getHeight(Heightmap.Types.WORLD_SURFACE,
                        community.rebuildX, community.rebuildZ);
                announceNearby(world, community.rebuildX, community.rebuildZ,
                        Component.literal("A new village has been built!")
                                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                        Component.literal("The refugees have rebuilt their home, safe from the glacier.")
                                .withStyle(ChatFormatting.DARK_GREEN),
                        320);
                world.playSound(null, new BlockPos(community.rebuildX, sy, community.rebuildZ),
                        SoundEvents.BELL_BLOCK, SoundSource.NEUTRAL, 3.0F, 1.0F);
                world.playSound(null, new BlockPos(community.rebuildX, sy, community.rebuildZ),
                        SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 2.0F, 1.0F);
            }
            return;
        }

        // Place a small batch of blocks each tick for visible construction
        int placed = 0;
        while (!community.buildQueue.isEmpty()
                && placed < IceWallConfig.VILLAGE_BUILD_BLOCKS_PER_TICK) {
            PlacedBlock pb = community.buildQueue.remove(0);
            if (world.getChunk(pb.pos().getX() >> 4, pb.pos().getZ() >> 4,
                    ChunkStatus.FULL, false) == null) {
                community.buildQueue.add(pb); // defer unloaded chunks to end
                break;
            }
            world.setBlock(pb.pos(), pb.state(), Block.UPDATE_CLIENTS);
            placed++;
        }

        // Sounds of villagers working
        if (world.getGameTime() % 30L == 0L) {
            int sy = world.getHeight(Heightmap.Types.WORLD_SURFACE,
                    community.rebuildX, community.rebuildZ);
            world.playSound(null, new BlockPos(community.rebuildX, sy, community.rebuildZ),
                    SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL,
                    1.5F, 0.8F + rng.nextFloat() * 0.4F);
        }
    }

    /**
     * Generates a fully procedural settlement build plan.
     *
     * Picks a material theme seeded on community position (deterministic — same
     * community always rebuilds identically, but different communities differ),
     * then places 1–4 buildings each with a unique archetype, footprint, wall
     * height, and roof style.  All blocks are queued bottom-to-top so the build
     * rises visibly in-game.
     */
    private List<PlacedBlock> generateBuildPlan(ServerLevel world, int cx, int cz, int villagers) {
        List<PlacedBlock> plan = new ArrayList<>();

        // Position-seeded RNG keeps each village deterministic across server restarts
        long seed = (long) cx * 341873128712L ^ (long) cz * 132897987541L;
        Random br = new Random(seed);

        BuildingTheme theme = THEMES[br.nextInt(THEMES.length)];
        int numBuildings = Math.min(4, Math.max(1, (villagers + 1) / 2));

        addLandmark(plan, world, cx, cz, theme, br);

        // Town hall — always present, placed north of the landmark
        int thY = world.getHeight(Heightmap.Types.WORLD_SURFACE, cx, cz - 22);
        addBuilding(plan, cx, thY, cz - 22, theme, Archetype.TOWN_HALL, 3, 4, br);

        // Roads radiating from the landmark to each building position
        int[][] offsets = buildOffsets(numBuildings, br);
        addRoads(plan, world, cx, cz, offsets, theme);

        // Gardens — one per 2 villagers, placed east/west of the roads
        int numGardens = Math.max(1, villagers / 3);
        int[][] gardenOffsets = gardenOffsets(numGardens, offsets, br);
        for (int[] go : gardenOffsets) {
            int gx = cx + go[0];
            int gz = cz + go[1];
            int gy = world.getHeight(Heightmap.Types.WORLD_SURFACE, gx, gz);
            addGarden(plan, gx, gy, gz, br);
        }

        for (int[] off : offsets) {
            int bx = cx + off[0];
            int bz = cz + off[1];
            int baseY = world.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
            Archetype arch = ARCHETYPES[br.nextInt(ARCHETYPES.length)];
            int half  = 2 + br.nextInt(2);                                      // 2→5×5, 3→7×7
            int wallH = (arch == Archetype.WATCHTOWER) ? 5 : (2 + br.nextInt(2)); // normal: 2 or 3
            addBuilding(plan, bx, baseY, bz, theme, arch, half, wallH, br);
        }

        return plan;
    }

    /** Scatters building positions around the settlement centre with slight random jitter. */
    private int[][] buildOffsets(int count, Random r) {
        int[][] slots = {{0, -18}, {-16, 8}, {16, 8}, {-16, -8}, {14, -16}, {0, 20}};
        int[][] result = new int[count][];
        for (int i = 0; i < count; i++) {
            int[] s = slots[i % slots.length];
            result[i] = new int[]{s[0] + r.nextInt(7) - 3, s[1] + r.nextInt(7) - 3};
        }
        return result;
    }

    /**
     * Places the community's central landmark.
     * Randomly one of: lantern post, bell podium, or communal campfire.
     */
    private void addLandmark(List<PlacedBlock> plan, ServerLevel world, int cx, int cz,
            BuildingTheme theme, Random r) {
        int y   = world.getHeight(Heightmap.Types.WORLD_SURFACE, cx, cz);
        int type = r.nextInt(3);
        if (type == 0) {
            // Tall lantern post
            plan.add(block(cx, y,     cz, theme.foundationBlock));
            plan.add(block(cx, y + 1, cz, theme.fenceBlock));
            plan.add(block(cx, y + 2, cz, theme.fenceBlock));
            plan.add(block(cx, y + 3, cz, Blocks.LANTERN));
        } else if (type == 1) {
            // Bell on raised plinth
            plan.add(block(cx, y,     cz, theme.foundationBlock));
            plan.add(block(cx, y + 1, cz, theme.foundationBlock));
            plan.add(block(cx, y + 2, cz, Blocks.BELL));
        } else {
            // Community campfire
            plan.add(block(cx, y,     cz, theme.foundationBlock));
            plan.add(block(cx, y + 1, cz, Blocks.CAMPFIRE));
        }
    }

    /**
     * Places one building: foundation → floor → walls (door gap) → roof → interior.
     *
     * @param half   half-size of footprint (2 = 5×5, 3 = 7×7)
     * @param wallH  wall height in blocks above floor (normal 2–3, watchtower 5)
     */
    private void addBuilding(List<PlacedBlock> plan, int cx, int baseY, int cz,
            BuildingTheme theme, Archetype arch, int half, int wallH, Random r) {
        // Foundation
        for (int dx = -half; dx <= half; dx++)
            for (int dz = -half; dz <= half; dz++)
                plan.add(block(cx + dx, baseY, cz + dz, theme.foundationBlock));

        // Floor
        for (int dx = -half; dx <= half; dx++)
            for (int dz = -half; dz <= half; dz++)
                plan.add(block(cx + dx, baseY + 1, cz + dz, theme.floorBlock));

        // Walls: perimeter only, wallH tall; door gap (2 blocks) faces south unless watchtower
        int entryDz = (arch == Archetype.WATCHTOWER) ? -half : half;
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                if (Math.abs(dx) != half && Math.abs(dz) != half) continue;
                boolean isDoor = (dx == 0 && dz == entryDz);
                for (int dy = 2; dy <= 1 + wallH; dy++) {
                    Block b = (isDoor && (dy == 2 || dy == 3)) ? Blocks.AIR : theme.wallBlock;
                    plan.add(block(cx + dx, baseY + dy, cz + dz, b));
                }
            }
        }

        addRoof(plan, cx, baseY, cz, theme, arch, half, wallH, r);
        addInterior(plan, cx, baseY, cz, arch, half, r);
    }

    /**
     * Adds a roof above the walls.  Style is randomly flat, ridge, or pyramid.
     * Watchtowers get an open parapet with corner lanterns instead.
     */
    private void addRoof(List<PlacedBlock> plan, int cx, int baseY, int cz,
            BuildingTheme theme, Archetype arch, int half, int wallH, Random r) {
        int roofY = baseY + wallH + 2;

        if (arch == Archetype.WATCHTOWER) {
            // Open crenellated parapet: fence perimeter, lanterns at corners
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    if (Math.abs(dx) != half && Math.abs(dz) != half) continue;
                    plan.add(block(cx + dx, roofY, cz + dz, theme.fenceBlock));
                    if (Math.abs(dx) == half && Math.abs(dz) == half)
                        plan.add(block(cx + dx, roofY + 1, cz + dz, Blocks.LANTERN));
                }
            }
            return;
        }

        int style = r.nextInt(3); // 0 = flat, 1 = ridge, 2 = stepped pyramid

        if (style == 0) {
            // Flat
            for (int dx = -half; dx <= half; dx++)
                for (int dz = -half; dz <= half; dz++)
                    plan.add(block(cx + dx, roofY, cz + dz, theme.roofBlock));

        } else if (style == 1) {
            // Flat base + X-axis ridge one block higher
            for (int dx = -half; dx <= half; dx++)
                for (int dz = -half; dz <= half; dz++)
                    plan.add(block(cx + dx, roofY, cz + dz, theme.roofBlock));
            for (int dx = -half; dx <= half; dx++)
                plan.add(block(cx + dx, roofY + 1, cz, theme.roofBlock));

        } else {
            // Stepped pyramid: each tier is a full square one block smaller and one block higher
            for (int tier = 0; tier <= half; tier++) {
                int r2 = half - tier;
                for (int dx = -r2; dx <= r2; dx++)
                    for (int dz = -r2; dz <= r2; dz++)
                        plan.add(block(cx + dx, roofY + tier, cz + dz, theme.roofBlock));
            }
        }
    }

    /**
     * Adds interior furnishings appropriate to the building's archetype.
     * All positions are kept inside the perimeter walls.
     */
    private void addInterior(List<PlacedBlock> plan, int cx, int baseY, int cz,
            Archetype arch, int half, Random r) {
        int fy = baseY + 2;  // first interior level (above floor planks)
        int ih = half - 1;   // max interior offset (one inside the wall)

        switch (arch) {
            case DWELLING -> {
                // Cosy: carpet strip, crafting table, lantern, flower pot
                plan.add(block(cx - 1, fy, cz - ih, Blocks.CRAFTING_TABLE));
                plan.add(block(cx,     fy, cz - ih, Blocks.LANTERN));
                plan.add(block(cx + 1, fy, cz - ih, Blocks.FLOWER_POT));
                plan.add(block(cx - 1, fy, cz,      randomCarpet(r)));
                plan.add(block(cx,     fy, cz,      randomCarpet(r)));
                plan.add(block(cx + 1, fy, cz,      randomCarpet(r)));
            }
            case WORKSHOP -> {
                // Smithy: blast furnace, smithing table, anvil, barrels, lantern
                plan.add(block(cx - 1, fy, cz - ih, Blocks.BLAST_FURNACE));
                plan.add(block(cx,     fy, cz - ih, Blocks.SMITHING_TABLE));
                plan.add(block(cx + 1, fy, cz - ih, Blocks.ANVIL));
                plan.add(block(cx,     fy, cz,      Blocks.LANTERN));
                plan.add(block(cx - ih, fy, cz + 1, Blocks.BARREL));
                plan.add(block(cx + ih, fy, cz + 1, Blocks.BARREL));
            }
            case LIBRARY -> {
                // Two rows of bookshelves on the back wall, lectern, lantern
                for (int dx = -ih; dx <= ih; dx++) {
                    plan.add(block(cx + dx, fy,     cz - ih, Blocks.BOOKSHELF));
                    plan.add(block(cx + dx, fy + 1, cz - ih, Blocks.BOOKSHELF));
                }
                plan.add(block(cx,     fy, cz, Blocks.LECTERN));
                plan.add(block(cx + 1, fy, cz, Blocks.LANTERN));
            }
            case STOREHOUSE -> {
                // Barrel corners, central chest, lantern above
                plan.add(block(cx - ih, fy, cz - ih, Blocks.BARREL));
                plan.add(block(cx + ih, fy, cz - ih, Blocks.BARREL));
                plan.add(block(cx - ih, fy, cz + ih, Blocks.BARREL));
                plan.add(block(cx + ih, fy, cz + ih, Blocks.BARREL));
                plan.add(block(cx,      fy, cz,      Blocks.CHEST));
                plan.add(block(cx,      fy, cz - 1,  Blocks.LANTERN));
            }
            case WATCHTOWER -> {
                // One lantern per floor on the back interior wall
                for (int floor = 0; floor < 5; floor++)
                    plan.add(block(cx, baseY + 2 + floor, cz - ih, Blocks.LANTERN));
            }
            case TOWN_HALL -> {
                // Grand interior: central meeting table (carpet), podium (lectern + lantern),
                // flanking bookshelves, bell, note block for ceremony.
                // Carpet runner down the middle
                for (int dz = -ih; dz <= ih; dz++)
                    plan.add(block(cx, fy, cz + dz, randomCarpet(r)));
                // Bookshelves on both side walls
                for (int dz2 = -ih; dz2 <= ih; dz2++) {
                    plan.add(block(cx - ih, fy,     cz + dz2, Blocks.BOOKSHELF));
                    plan.add(block(cx + ih, fy,     cz + dz2, Blocks.BOOKSHELF));
                    plan.add(block(cx - ih, fy + 1, cz + dz2, Blocks.BOOKSHELF));
                    plan.add(block(cx + ih, fy + 1, cz + dz2, Blocks.BOOKSHELF));
                }
                // Mayor's podium at the north end
                plan.add(block(cx,     fy, cz - ih, Blocks.LECTERN));
                plan.add(block(cx - 1, fy, cz - ih, Blocks.LANTERN));
                plan.add(block(cx + 1, fy, cz - ih, Blocks.LANTERN));
                plan.add(block(cx,     fy, cz,      Blocks.NOTE_BLOCK));
                plan.add(block(cx,     fy, cz + 1,  Blocks.BELL));
            }
            case GARDEN -> {
                // Not used via addBuilding — handled by addGarden directly
            }
        }
    }

    // -----------------------------------------------------------------------
    // Building palette — themes and archetypes
    // -----------------------------------------------------------------------

    private enum Archetype { DWELLING, WORKSHOP, LIBRARY, STOREHOUSE, WATCHTOWER, TOWN_HALL, GARDEN }

    // ARCHETYPES available for random assignment to ordinary buildings
    // (TOWN_HALL and GARDEN are placed deliberately, not randomly)
    private static final Archetype[] ARCHETYPES = {
        Archetype.DWELLING, Archetype.WORKSHOP, Archetype.LIBRARY,
        Archetype.STOREHOUSE, Archetype.WATCHTOWER
    };

    /**
     * Material theme: each community uses one palette consistently across all its
     * buildings so the settlement has a coherent visual identity.
     */
    private static final class BuildingTheme {
        final Block wallBlock, floorBlock, roofBlock, foundationBlock, fenceBlock;
        BuildingTheme(Block w, Block fl, Block ro, Block fo, Block fe) {
            wallBlock = w; floorBlock = fl; roofBlock = ro; foundationBlock = fo; fenceBlock = fe;
        }
    }

    private static final BuildingTheme[] THEMES = {
        // Oak woodland
        new BuildingTheme(Blocks.OAK_LOG,      Blocks.OAK_PLANKS,    Blocks.OAK_PLANKS,    Blocks.COBBLESTONE, Blocks.OAK_FENCE),
        // Spruce alpine
        new BuildingTheme(Blocks.SPRUCE_LOG,   Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.STONE,       Blocks.SPRUCE_FENCE),
        // Birch meadow
        new BuildingTheme(Blocks.BIRCH_LOG,    Blocks.BIRCH_PLANKS,  Blocks.BIRCH_PLANKS,  Blocks.COBBLESTONE, Blocks.BIRCH_FENCE),
        // Acacia savanna
        new BuildingTheme(Blocks.ACACIA_LOG,   Blocks.ACACIA_PLANKS, Blocks.ACACIA_PLANKS, Blocks.SANDSTONE,   Blocks.ACACIA_FENCE),
        // Stone masonry
        new BuildingTheme(Blocks.STONE_BRICKS, Blocks.OAK_PLANKS,   Blocks.STONE_BRICKS,  Blocks.COBBLESTONE, Blocks.OAK_FENCE),
    };

    private static final Block[] CARPETS = {
        Blocks.RED_CARPET,    Blocks.BLUE_CARPET,   Blocks.GREEN_CARPET,
        Blocks.YELLOW_CARPET, Blocks.ORANGE_CARPET, Blocks.PURPLE_CARPET,
        Blocks.CYAN_CARPET,   Blocks.WHITE_CARPET,
    };

    private Block randomCarpet(Random r) { return CARPETS[r.nextInt(CARPETS.length)]; }

    /**
     * Builds gravel roads connecting the village centre to each building offset.
     * Uses Bresenham-style stepping (axis-aligned for simplicity) and places
     * lantern posts every 8 blocks for lighting.
     */
    private void addRoads(List<PlacedBlock> plan, ServerLevel world,
            int cx, int cz, int[][] offsets, BuildingTheme theme) {
        for (int[] off : offsets) {
            int tx = cx + off[0];
            int tz = cz + off[1];

            // X leg
            int step = (tx >= cx) ? 1 : -1;
            for (int x = cx; x != tx; x += step) {
                int y = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, cz);
                plan.add(block(x, y, cz,     Blocks.GRAVEL));
                plan.add(block(x, y, cz + 1, Blocks.GRAVEL));
                if (Math.abs(x - cx) % 8 == 0)
                    plan.add(block(x, y + 1, cz - 1, Blocks.LANTERN));
            }
            // Z leg
            step = (tz >= cz) ? 1 : -1;
            for (int z = cz; z != tz; z += step) {
                int y = world.getHeight(Heightmap.Types.WORLD_SURFACE, tx, z);
                plan.add(block(tx,     y, z, Blocks.GRAVEL));
                plan.add(block(tx + 1, y, z, Blocks.GRAVEL));
                if (Math.abs(z - cz) % 8 == 0)
                    plan.add(block(tx - 1, y + 1, z, Blocks.LANTERN));
            }
        }
    }

    /**
     * Returns offsets for garden plots, placed slightly beside building positions
     * so they don't overlap with buildings.
     */
    private int[][] gardenOffsets(int count, int[][] buildingOffsets, Random r) {
        int[][] result = new int[count][];
        for (int i = 0; i < count; i++) {
            int[] base = buildingOffsets[i % buildingOffsets.length];
            // Shift 10 blocks to one side (east for even i, west for odd)
            int xShift = (i % 2 == 0) ? 12 : -12;
            result[i] = new int[]{
                base[0] + xShift + r.nextInt(5) - 2,
                base[1] + r.nextInt(5) - 2
            };
        }
        return result;
    }

    /**
     * Places a fenced garden: farmland in a 5×5 grid with crops, flowers along the
     * edges, a composter, and corner flower pots for decoration.
     * The fence perimeter has a gate gap on the south side.
     */
    private void addGarden(List<PlacedBlock> plan, int cx, int baseY, int cz, Random r) {
        int half = 3;
        // Dirt base
        for (int dx = -half; dx <= half; dx++)
            for (int dz = -half; dz <= half; dz++)
                plan.add(block(cx + dx, baseY, cz + dz, Blocks.DIRT));

        // Farmland interior (3×3 in the centre, keeping 1 block around edge for flowers)
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                plan.add(block(cx + dx, baseY + 1, cz + dz, Blocks.FARMLAND));

        // Crops on farmland — random mix of wheat/carrots/potatoes
        Block[] crops = {Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES};
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                plan.add(block(cx + dx, baseY + 2, cz + dz, crops[r.nextInt(crops.length)]));

        // Flower border on the dirt edge (one block inside fence)
        Block[] flowers = {Blocks.DANDELION, Blocks.POPPY, Blocks.CORNFLOWER, Blocks.TORCHFLOWER};
        for (int dx = -half + 1; dx <= half - 1; dx++) {
            for (int dz = -half + 1; dz <= half - 1; dz++) {
                // Only on the inner ring
                if (Math.abs(dx) != half - 1 && Math.abs(dz) != half - 1) continue;
                // Skip the farmland area
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) continue;
                plan.add(block(cx + dx, baseY + 1, cz + dz, Blocks.GRASS_BLOCK));
                plan.add(block(cx + dx, baseY + 2, cz + dz, flowers[r.nextInt(flowers.length)]));
            }
        }

        // Fence perimeter with gate gap on south side
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                if (Math.abs(dx) != half && Math.abs(dz) != half) continue;
                boolean isGate = (dx == 0 && dz == half);
                plan.add(block(cx + dx, baseY + 1, cz + dz,
                        isGate ? Blocks.AIR : Blocks.OAK_FENCE));
            }
        }

        // Composter and water source (irrigation)
        plan.add(block(cx - 2, baseY + 1, cz, Blocks.COMPOSTER));
        plan.add(block(cx + 2, baseY + 1, cz, Blocks.WATER_CAULDRON));
    }

    /** Convenience shorthand to avoid repeating `.defaultBlockState()` everywhere. */
    private static PlacedBlock block(int x, int y, int z, Block b) {
        return new PlacedBlock(new BlockPos(x, y, z), b.defaultBlockState());
    }

    /**
     * Teleports surviving villagers near the new build site so they witness construction.
     */
    private void relocateVillagersToSite(ServerLevel world, Community community) {
        int sy = world.getHeight(Heightmap.Types.WORLD_SURFACE,
                community.rebuildX, community.rebuildZ);
        for (UUID uid : community.members) {
            net.minecraft.world.entity.Entity e = world.getEntityInAnyDimension(uid);
            if (e instanceof Villager villager && !villager.isRemoved()) {
                double ox = (rng.nextDouble() - 0.5) * 20;
                double oz = rng.nextDouble() * 10 + 5; // south of the build centre
                villager.teleportTo(
                        community.rebuildX + ox,
                        (double) sy,
                        community.rebuildZ + oz);
            }
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
